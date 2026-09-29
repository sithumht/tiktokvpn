package warpscout

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/amnezia-vpn/amneziawg-go/conn"
	"github.com/amnezia-vpn/amneziawg-go/device"

	"github.com/sithumht/tiktokvpn/core"
)

// Handshake budget before the endpoint is declared unreachable: the first
// handshake on a healthy path lands in one round trip, and the wait doubles
// from 2ms, so anything near this long is a dead or filtered endpoint.
const connectHandshakeTimeout = 15 * time.Second

const connectStatsInterval = time.Second

// Connect runs the device tunnel on the VPN interface the app established
// until ctx is canceled. The engine allows one operation at a time, so a live
// tunnel also holds off scans and registrations - which is what we want: they
// only make sense while the interface is down.
func (b *MobileBackend) Connect(ctx context.Context, input core.Account, opts core.ConnectOptions, sink core.EventSink) error {
	b.mu.Lock()
	defer b.mu.Unlock()

	resetMobileState()

	// The descriptor is created first: once it exists this function owns it and
	// every exit path below must close it.
	tunDev, err := newFdTun(opts.TunFd, opts.MTU)
	if err != nil {
		return mobileFailure("tunnel_setup_failed", err, false)
	}
	defer tunDev.Close()

	a, err := mobileAccount(input)
	if err != nil {
		return err
	}
	applyAccount(a)

	if strings.TrimSpace(opts.Endpoint) == "" {
		return mobileFailure("endpoint_required", errors.New("endpoint is required"), false)
	}
	endpoint, err := parseEndpointSpec("endpoint", strings.TrimSpace(opts.Endpoint))
	if err != nil {
		return mobileFailure("invalid_endpoint", err, false)
	}

	outerProto := opts.Protocol
	if outerProto == "" {
		outerProto = core.ProtocolAWG
	}
	innerProto := opts.InnerProtocol
	if innerProto == "" {
		innerProto = outerProto
	}
	nested := strings.TrimSpace(opts.Through) != ""
	if !nested {
		innerProto = outerProto
	}
	if outerProto == core.ProtocolMASQUEH3 || outerProto == core.ProtocolMASQUEH2 ||
		innerProto == core.ProtocolMASQUEH3 || innerProto == core.ProtocolMASQUEH2 {
		return mobileFailure("unsupported_protocol", errors.New("this mode is not supported"), false)
	}
	// Under nesting proto is the tunnel that crosses the network (the outer
	// one) and inner-proto the tunnel carried inside it, mirroring a scan.
	probeProto := outerProto
	if nested {
		probeProto = innerProto
	}
	run, err := parseProto(string(probeProto))
	if err != nil {
		return mobileFailure("invalid_protocol", err, false)
	}

	// Reproduce exactly the parameters the endpoint was found with: the
	// endpoint only proved itself under them.
	if outerProto == core.ProtocolAWG || innerProto == core.ProtocolAWG {
		if opts.AWGJunkCount != 0 {
			awgJc = opts.AWGJunkCount
		}
		if opts.AWGJunkMin != 0 {
			awgJmin = opts.AWGJunkMin
		}
		if opts.AWGJunkMax != 0 {
			awgJmax = opts.AWGJunkMax
		}
		if opts.AWGI1 != "" {
			awgI1 = opts.AWGI1
		}
		if awgJc < junkCountLimitMin || awgJc > junkCountLimitMax || awgJmin > awgJmax || awgJmax > tunnelMTU {
			return mobileFailure("invalid_junk_parameters", errors.New("invalid connection parameters"), false)
		}
	}

	timeout := time.Duration(positiveOr(opts.TimeoutSec, int(connectHandshakeTimeout/time.Second))) * time.Second

	if nested {
		n, err := dialOuter(ctx, options{
			proto:   string(outerProto),
			through: strings.TrimSpace(opts.Through),
			ipv6:    false,
		}, timeout)
		if err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return operationFailure(ctx, "outer_tunnel_failed", err, true)
		}
		outer = n
		defer func() {
			outer.tunnel.Close()
			outer = nil
		}()
	}

	bind := conn.Bind(newDeviceBind(scanInterface))
	if outer != nil {
		bind = newTunnelBind(outer)
	}
	dev := device.NewDevice(tunDev, bind, device.NewLogger(device.LogLevelSilent, ""))
	stopped := false
	stop := func() {
		if stopped {
			return
		}
		stopped = true
		dev.Close()
	}
	defer stop()

	base, err := baseUAPI(run.isAWG())
	if err != nil {
		return mobileFailure("tunnel_setup_failed", err, false)
	}
	peer, err := peerUAPI(endpoint)
	if err != nil {
		return mobileFailure("tunnel_setup_failed", err, false)
	}
	if err := dev.IpcSet(base); err != nil {
		return mobileFailure("tunnel_setup_failed", err, false)
	}
	if err := dev.Up(); err != nil {
		return mobileFailure("tunnel_setup_failed", err, false)
	}
	if err := dev.IpcSet(peer); err != nil {
		return mobileFailure("tunnel_setup_failed", err, false)
	}

	emitConnect(sink, "handshaking", endpoint, nil)
	if !waitHandshake(ctx, dev, timeout) {
		if ctx.Err() != nil {
			return nil
		}
		return operationFailure(ctx, "endpoint_unreachable", fmt.Errorf("%s did not answer", endpoint), true)
	}
	emitConnect(sink, "connected", endpoint, tunDev.stats(dev))

	ticker := time.NewTicker(connectStatsInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			emitConnect(sink, "stats", endpoint, tunDev.stats(dev))
		}
	}
}

func (t *fdTun) stats(dev *device.Device) core.TunnelStats {
	stats := core.TunnelStats{RxBytes: t.rxBytes(), TxBytes: t.txBytes()}
	if conf, err := dev.IpcGet(); err == nil {
		stats.HandshakeUnixMs = lastHandshakeMs(conf)
	}
	return stats
}

const handshakeSecondsKey = "last_handshake_time_sec="
const handshakeNanosKey = "last_handshake_time_nsec="

func lastHandshakeMs(conf string) int64 {
	sec := int64(configInt(conf, handshakeSecondsKey))
	if sec <= 0 {
		return 0
	}
	nsec := int64(configInt(conf, handshakeNanosKey))
	return sec*1000 + nsec/int64(time.Millisecond)
}

func configInt(conf, key string) int {
	i := strings.Index(conf, key)
	if i < 0 {
		return 0
	}
	value := i + len(key)
	end := value
	for end < len(conf) && conf[end] >= '0' && conf[end] <= '9' {
		end++
	}
	if end == value {
		return 0
	}
	var n int
	for _, c := range conf[value:end] {
		n = n*10 + int(c-'0')
	}
	return n
}

func emitConnect(sink core.EventSink, kind, endpoint string, payload any) {
	if sink == nil {
		return
	}
	event := core.ProgressEvent{
		Operation: core.OperationConnect,
		Type:      kind,
		Message:   endpoint,
	}
	if payload != nil {
		if raw, err := json.Marshal(payload); err == nil {
			event.Payload = raw
		}
	}
	sink(event)
}
