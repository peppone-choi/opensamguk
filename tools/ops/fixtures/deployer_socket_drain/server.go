// Synthetic plain HTTP/1.1 Go 1.23 fixture. No production code or inputs.
package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

func must(err error) {
	if err != nil {
		panic(err)
	}
}

func event(name string, value any) {
	data, err := json.Marshal(value)
	must(err)
	file, err := os.CreateTemp("/events", ".event-")
	must(err)
	_, err = file.Write(data)
	must(err)
	must(file.Close())
	must(os.Rename(file.Name(), "/events/"+name))
}

func waitControl(name string) {
	timer := time.NewTicker(10 * time.Millisecond)
	defer timer.Stop()
	for {
		if _, err := os.Stat("/control/" + name); err == nil {
			return
		} else if !os.IsNotExist(err) {
			panic(err)
		}
		<-timer.C
	}
}

type connectionKey struct{}

func serve() {
	var connections sync.Map
	server := &http.Server{ReadHeaderTimeout: 10 * time.Second}
	server.ConnContext = func(ctx context.Context, conn net.Conn) context.Context {
		return context.WithValue(ctx, connectionKey{}, conn)
	}
	server.ConnState = func(conn net.Conn, state http.ConnState) {
		if state == http.StateClosed {
			if id, ok := connections.LoadAndDelete(conn); ok {
				event("closed-"+id.(string), map[string]any{"state": "closed"})
			}
		}
	}
	server.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/health" {
			fmt.Fprintln(w, "healthy")
			return
		}
		id := strings.TrimPrefix(r.URL.Path, "/hold/")
		if id != "held" && id != "reset" {
			http.NotFound(w, r)
			return
		}
		conn := r.Context().Value(connectionKey{}).(*net.TCPConn)
		connections.Store(conn, id)
		raw, err := conn.SyscallConn()
		must(err)
		var fd, inode int
		must(raw.Control(func(value uintptr) {
			fd = int(value)
			link, err := os.Readlink(fmt.Sprintf("/proc/self/fd/%d", fd))
			must(err)
			inode, err = strconv.Atoi(strings.TrimSuffix(strings.TrimPrefix(link, "socket:["), "]"))
			must(err)
		}))
		var blocked atomic.Bool
		blocked.Store(true)
		event("entered-"+id, map[string]any{"fd": fd, "inode": inode, "blocked": true})
		go func() {
			<-r.Context().Done()
			event("cancelled-"+id, map[string]any{"fd": fd, "inode": inode, "blocked": blocked.Load()})
		}()
		// Deliberately ignore request cancellation while the handler owns its FD.
		waitControl("release-" + id)
		blocked.Store(false)
		fmt.Fprintln(w, "released")
		event("returned-"+id, map[string]any{"released": true})
	})
	listener, err := net.Listen("tcp", ":9000")
	must(err)
	event("ready", map[string]any{"go_version": runtime.Version(), "pid": os.Getpid()})
	must(server.Serve(listener))
}

func client(mode, address, id string) {
	conn, err := net.DialTimeout("tcp", address, 3*time.Second)
	must(err)
	defer conn.Close()
	_, err = fmt.Fprintf(conn, "GET /hold/%s HTTP/1.1\r\nHost: fixture\r\nConnection: close\r\n\r\n", id)
	must(err)
	if mode == "reset" {
		waitControl("rst-" + id)
		must(conn.(*net.TCPConn).SetLinger(0))
		must(conn.Close())
		event("rst-"+id, map[string]any{"rst_sent": true})
		return
	}
	response, err := http.ReadResponse(bufio.NewReader(conn), nil)
	must(err)
	body, err := io.ReadAll(response.Body)
	must(err)
	must(response.Body.Close())
	event("response-"+id, map[string]any{"status": response.StatusCode, "body": string(body)})
}

func main() {
	switch os.Args[1] {
	case "server":
		serve()
	case "idle":
		for {
			time.Sleep(time.Hour)
		}
	case "event":
		name := os.Args[2]
		if filepath.Base(name) != name {
			panic("invalid event")
		}
		data, err := os.ReadFile("/events/" + name)
		if os.IsNotExist(err) {
			return
		}
		must(err)
		fmt.Print(string(data))
	case "signal":
		name := os.Args[2]
		if filepath.Base(name) != name {
			panic("invalid signal")
		}
		must(os.WriteFile("/control/"+name, []byte("release"), 0600))
	case "hold", "reset":
		client(os.Args[1], os.Args[2], os.Args[3])
	case "reject":
		conn, err := net.DialTimeout("tcp", os.Args[2], 3*time.Second)
		if conn != nil {
			conn.Close()
		}
		must(json.NewEncoder(os.Stdout).Encode(map[string]any{
			"address": os.Args[2], "rejected": err != nil, "connection_refused": errors.Is(err, syscall.ECONNREFUSED),
		}))
	case "health":
		client := &http.Client{Timeout: 3 * time.Second, Transport: &http.Transport{DisableKeepAlives: true}}
		response, err := client.Get("http://" + os.Args[2] + "/health")
		must(err)
		_, err = io.Copy(io.Discard, response.Body)
		must(err)
		must(response.Body.Close())
		must(json.NewEncoder(os.Stdout).Encode(map[string]any{"status": response.StatusCode}))
	default:
		panic("unknown fixture action")
	}
}
