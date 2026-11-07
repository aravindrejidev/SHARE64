#pragma once

#include <atomic>
#include <string>

#include "share64/types.h"

namespace s64 {
namespace net {

// Returns a connected, blocking socket or -1 (err set). Polls in 100 ms slices so `abort` is honoured.
int connect_tcp(const std::string& host, uint16_t port, int timeout_ms, const std::atomic<bool>& abort,
                Err& err);
// Dual-stack listener (IPv4 fallback). port 0 = ephemeral. Returns fd or -1.
int listen_tcp(uint16_t port, uint16_t* bound, Err& err);
// Returns a client fd, or -1 when `stop` is raised / on a transient error.
int accept_tcp(int listen_fd, const std::atomic<bool>& stop);

void tune(int fd, int io_timeout_ms);  // NODELAY, keepalive, TCP_USER_TIMEOUT, buffers, timeouts
void set_recv_timeout(int fd, int ms);

Err send_all(int fd, const void* p, size_t n, const std::atomic<bool>& abort, bool more = false);
Err recv_all(int fd, void* p, size_t n, const std::atomic<bool>& abort);

}  // namespace net
}  // namespace s64
