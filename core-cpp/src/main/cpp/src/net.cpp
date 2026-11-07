#include "net.h"

#include <arpa/inet.h>
#include <fcntl.h>
#include <netdb.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <unistd.h>

#include <algorithm>
#include <cerrno>
#include <cstdio>
#include <cstring>

#include "util.h"

#ifndef TCP_USER_TIMEOUT
#define TCP_USER_TIMEOUT 18
#endif

namespace s64 {
namespace net {

int connect_tcp(const std::string& host, uint16_t port, int timeout_ms, const std::atomic<bool>& abort,
                Err& err) {
  addrinfo hints;
  std::memset(&hints, 0, sizeof hints);
  hints.ai_family = AF_UNSPEC;
  hints.ai_socktype = SOCK_STREAM;
  char ps[8];
  std::snprintf(ps, sizeof ps, "%u", static_cast<unsigned>(port));
  addrinfo* res = nullptr;
  if (getaddrinfo(host.c_str(), ps, &hints, &res) != 0 || !res) {
    err = Err::Io;
    return -1;
  }
  const int64_t deadline = now_ms() + timeout_ms;
  int fd = -1;
  for (addrinfo* ai = res; ai; ai = ai->ai_next) {
    fd = ::socket(ai->ai_family, ai->ai_socktype | SOCK_CLOEXEC, ai->ai_protocol);
    if (fd < 0) continue;
    const int fl = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, fl | O_NONBLOCK);
    bool ok = ::connect(fd, ai->ai_addr, ai->ai_addrlen) == 0;
    if (!ok && errno == EINPROGRESS) {
      for (;;) {
        if (abort.load(std::memory_order_acquire)) break;
        const int64_t left = deadline - now_ms();
        if (left <= 0) break;
        pollfd p;
        p.fd = fd;
        p.events = POLLOUT;
        p.revents = 0;
        const int pr = ::poll(&p, 1, static_cast<int>(std::min<int64_t>(left, 100)));
        if (pr > 0) {
          int soerr = 0;
          socklen_t sl = sizeof soerr;
          getsockopt(fd, SOL_SOCKET, SO_ERROR, &soerr, &sl);
          ok = soerr == 0;
          break;
        }
        if (pr < 0 && errno != EINTR) break;
      }
    }
    if (ok) {
      fcntl(fd, F_SETFL, fl);
      break;
    }
    ::close(fd);
    fd = -1;
    if (abort.load(std::memory_order_acquire) || now_ms() >= deadline) break;
  }
  freeaddrinfo(res);
  if (fd < 0) err = abort.load() ? Err::Cancelled : (now_ms() >= deadline ? Err::Timeout : Err::Io);
  return fd;
}

int listen_tcp(uint16_t port, uint16_t* bound, Err& err) {
  int one = 1;
  int fd = ::socket(AF_INET6, SOCK_STREAM | SOCK_CLOEXEC, 0);
  bool v6 = fd >= 0;
  if (v6) {
    int zero = 0;
    setsockopt(fd, IPPROTO_IPV6, IPV6_V6ONLY, &zero, sizeof zero);
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    sockaddr_in6 a;
    std::memset(&a, 0, sizeof a);
    a.sin6_family = AF_INET6;
    a.sin6_addr = in6addr_any;
    a.sin6_port = htons(port);
    if (::bind(fd, reinterpret_cast<sockaddr*>(&a), sizeof a) != 0) {
      ::close(fd);
      fd = -1;
      v6 = false;
    }
  }
  if (!v6) {
    fd = ::socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
      err = Err::Io;
      return -1;
    }
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof one);
    sockaddr_in a;
    std::memset(&a, 0, sizeof a);
    a.sin_family = AF_INET;
    a.sin_addr.s_addr = htonl(INADDR_ANY);
    a.sin_port = htons(port);
    if (::bind(fd, reinterpret_cast<sockaddr*>(&a), sizeof a) != 0) {
      err = errno == EADDRINUSE ? Err::Busy : Err::Io;
      ::close(fd);
      return -1;
    }
  }
  if (::listen(fd, 16) != 0) {
    err = Err::Io;
    ::close(fd);
    return -1;
  }
  sockaddr_storage ss;
  socklen_t sl = sizeof ss;
  if (bound && getsockname(fd, reinterpret_cast<sockaddr*>(&ss), &sl) == 0) {
    *bound = ss.ss_family == AF_INET6 ? ntohs(reinterpret_cast<sockaddr_in6*>(&ss)->sin6_port)
                                      : ntohs(reinterpret_cast<sockaddr_in*>(&ss)->sin_port);
  }
  return fd;
}

int accept_tcp(int listen_fd, const std::atomic<bool>& stop) {
  while (!stop.load(std::memory_order_acquire)) {
    pollfd p;
    p.fd = listen_fd;
    p.events = POLLIN;
    p.revents = 0;
    const int pr = ::poll(&p, 1, 200);
    if (pr < 0 && errno != EINTR) return -1;
    if (pr > 0) {
      const int fd = ::accept4(listen_fd, nullptr, nullptr, SOCK_CLOEXEC);
      if (fd >= 0) return fd;
      if (errno != EINTR && errno != EAGAIN && errno != ECONNABORTED) return -1;
    }
  }
  return -1;
}

void set_recv_timeout(int fd, int ms) {
  timeval tv;
  tv.tv_sec = ms / 1000;
  tv.tv_usec = (ms % 1000) * 1000;
  setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
}

void tune(int fd, int io_timeout_ms) {
  int one = 1;
  setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof one);
  setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof one);
  int idle = 5, intvl = 2, cnt = 3;
  setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE, &idle, sizeof idle);
  setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &intvl, sizeof intvl);
  setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &cnt, sizeof cnt);
  unsigned int uto = static_cast<unsigned int>(io_timeout_ms);
  setsockopt(fd, IPPROTO_TCP, TCP_USER_TIMEOUT, &uto, sizeof uto);
  int buf = 1 << 20;
  setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &buf, sizeof buf);
  setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &buf, sizeof buf);
  timeval tv;
  tv.tv_sec = io_timeout_ms / 1000;
  tv.tv_usec = (io_timeout_ms % 1000) * 1000;
  setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
  setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
}

Err send_all(int fd, const void* p, size_t n, const std::atomic<bool>& abort, bool more) {
  const uint8_t* b = static_cast<const uint8_t*>(p);
  while (n) {
    const ssize_t r = ::send(fd, b, n, MSG_NOSIGNAL | (more ? MSG_MORE : 0));
    if (r > 0) {
      b += r;
      n -= static_cast<size_t>(r);
    } else if (r < 0 && errno == EINTR) {
      if (abort.load(std::memory_order_acquire)) return Err::Io;
    } else if (r < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
      return Err::Timeout;
    } else {
      return Err::Io;
    }
  }
  return Err::Ok;
}

Err recv_all(int fd, void* p, size_t n, const std::atomic<bool>& abort) {
  uint8_t* b = static_cast<uint8_t*>(p);
  while (n) {
    const ssize_t r = ::recv(fd, b, n, 0);
    if (r > 0) {
      b += r;
      n -= static_cast<size_t>(r);
    } else if (r == 0) {
      return Err::Io;  // peer closed
    } else if (errno == EINTR) {
      if (abort.load(std::memory_order_acquire)) return Err::Io;
    } else if (errno == EAGAIN || errno == EWOULDBLOCK) {
      return Err::Timeout;
    } else {
      return Err::Io;
    }
  }
  return Err::Ok;
}

}  // namespace net
}  // namespace s64
