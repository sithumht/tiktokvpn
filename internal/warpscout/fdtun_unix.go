//go:build !windows

package warpscout

import "golang.org/x/sys/unix"

func setNonblock(fd int) error {
	return unix.SetNonblock(fd, true)
}

func dupFd(fd int) (int, error) {
	return unix.Dup(fd)
}

func closeFd(fd int) {
	_ = unix.Close(fd)
}
