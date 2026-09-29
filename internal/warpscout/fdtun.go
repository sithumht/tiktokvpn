package warpscout

import (
	"fmt"
	"os"
	"sync/atomic"

	"github.com/amnezia-vpn/amneziawg-go/tun"
)

// fdTun adapts the VPN interface file descriptor the app hands over (from the
// system's VPN builder) to the tun.Device the tunnel core reads and writes
// packets through. The device owns the descriptor once it is created and
// closes it on Close, exactly like the platform tun implementations do.
type fdTun struct {
	file   *os.File
	mtu    int
	events chan tun.Event
	rx     atomic.Int64 // bytes delivered to the phone (download)
	tx     atomic.Int64 // bytes read from the phone (upload)
	closed atomic.Bool
}

func newFdTun(fd, mtu int) (*fdTun, error) {
	if fd < 0 {
		return nil, fmt.Errorf("tunnel descriptor is missing")
	}
	// The app keeps its own descriptor for the whole tunnel so it can always
	// tear the interface down, even if this side never gets to run. Working on
	// a duplicate keeps the two closes on separate descriptors: closing the
	// same number twice could land on an unrelated file the runtime opened.
	dup, err := dupFd(fd)
	if err != nil {
		return nil, fmt.Errorf("tunnel descriptor: %w", err)
	}
	if mtu <= 0 || mtu > 65535 {
		mtu = tunnelMTU
	}
	// Non-blocking so the descriptor joins the runtime poller: reads then wake
	// up when the device closes, which is what teardown depends on.
	if err := setNonblock(dup); err != nil {
		closeFd(dup)
		return nil, fmt.Errorf("tunnel descriptor: %w", err)
	}
	file := os.NewFile(uintptr(dup), "tun0")
	if file == nil {
		closeFd(dup)
		return nil, fmt.Errorf("tunnel descriptor is invalid")
	}
	device := &fdTun{file: file, mtu: mtu, events: make(chan tun.Event, 2)}
	device.events <- tun.EventUp
	return device, nil
}

func (t *fdTun) File() *os.File { return t.file }

// The interface delivers one packet per read; sizes holds that one length.
func (t *fdTun) Read(bufs [][]byte, sizes []int, offset int) (int, error) {
	n, err := t.file.Read(bufs[0][offset:])
	if err != nil {
		return 0, err
	}
	sizes[0] = n
	t.tx.Add(int64(n))
	return 1, nil
}

// One write per packet: the descriptor has no batching, so a partial burst
// stops at the first failing packet with the packets already written counted.
func (t *fdTun) Write(bufs [][]byte, offset int) (int, error) {
	written := 0
	for _, buf := range bufs {
		n, err := t.file.Write(buf[offset:])
		if n > 0 {
			t.rx.Add(int64(n))
			written++
		}
		if err != nil {
			if written > 0 {
				break
			}
			return 0, err
		}
	}
	return written, nil
}

func (t *fdTun) MTU() (int, error)        { return t.mtu, nil }
func (t *fdTun) Name() (string, error)    { return "tun0", nil }
func (t *fdTun) Events() <-chan tun.Event { return t.events }
func (t *fdTun) BatchSize() int           { return 1 }

func (t *fdTun) Close() error {
	if !t.closed.CompareAndSwap(false, true) {
		return nil
	}
	err := t.file.Close()
	close(t.events)
	return err
}

func (t *fdTun) rxBytes() int64 { return t.rx.Load() }
func (t *fdTun) txBytes() int64 { return t.tx.Load() }
