package warpscout

import "sync/atomic"

// protectFn is installed by the app through mobileapi while the VPN interface
// is up. Every host socket the tunnel opens is passed through it so its packets
// leave on the physical network instead of looping back into the interface
// they are supposed to carry.
var protectFn atomic.Pointer[func(fd int)]

func SetSocketProtector(fn func(fd int)) {
	if fn == nil {
		protectFn.Store(nil)
		return
	}
	protectFn.Store(&fn)
}

func protectFD(fd int) {
	if p := protectFn.Load(); p != nil {
		(*p)(fd)
	}
}
