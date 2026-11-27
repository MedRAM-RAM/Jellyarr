// Jellyarr: Android has no /etc/resolv.conf, so Go's resolver would query 127.0.0.1:53 and every lookup
// (including the edge discovery cloudflared needs to connect) would fail. When Jellyarr starts the tunnel it
// passes the device's DNS servers in JELLYARR_DNS; this file points the default resolver at them.
// Without that variable cloudflared behaves exactly like the upstream build.
package main

import (
	"context"
	"net"
	"os"
	"strings"
	"sync/atomic"
	"time"
)

func init() {
	list := strings.TrimSpace(os.Getenv("JELLYARR_DNS"))
	if list == "" {
		return
	}
	var servers []string
	for _, s := range strings.Split(list, ",") {
		s = strings.TrimSpace(s)
		if s == "" {
			continue
		}
		if _, _, err := net.SplitHostPort(s); err != nil {
			s = net.JoinHostPort(s, "53")
		}
		servers = append(servers, s)
	}
	if len(servers) == 0 {
		return
	}
	net.DefaultResolver = jellyarrResolver(servers)
}

// next rotates the first server tried. A UDP "dial" to a dead server still succeeds, so without rotation the
// resolver would retry the same dead server on every attempt instead of moving on to the next one.
var next uint32

// jellyarrResolver ignores the address Go derives from resolv.conf and dials the given servers, one per attempt.
func jellyarrResolver(servers []string) *net.Resolver {
	return &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
			d := net.Dialer{Timeout: 4 * time.Second}
			start := int(atomic.AddUint32(&next, 1)-1) % len(servers)
			var last error
			for i := 0; i < len(servers); i++ {
				c, err := d.DialContext(ctx, network, servers[(start+i)%len(servers)])
				if err == nil {
					return c, nil
				}
				last = err
			}
			return nil, last
		},
	}
}
