//go:build windows

package warpscout

import "golang.org/x/sys/windows"

// The Windows build only exercises this path in tests; the descriptor handed
// over there is a plain handle, so there is no poller to join.
func setNonblock(int) error {
	return nil
}

func dupFd(fd int) (int, error) {
	var target windows.Handle
	err := windows.DuplicateHandle(
		windows.CurrentProcess(),
		windows.Handle(fd),
		windows.CurrentProcess(),
		&target,
		0,
		false,
		windows.DUPLICATE_SAME_ACCESS,
	)
	if err != nil {
		return 0, err
	}
	return int(target), nil
}

func closeFd(fd int) {
	_ = windows.CloseHandle(windows.Handle(fd))
}
