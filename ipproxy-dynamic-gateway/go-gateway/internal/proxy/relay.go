package proxy

import (
	"io"
	"net"
	"sync"
	"time"
)

var bufferPool = sync.Pool{New: func() any {
	b := make([]byte, 32*1024)
	return &b
}}

func Relay(a, b net.Conn, onAtoB, onBtoA func(int64)) {
	var once sync.Once
	closeBoth := func() {
		a.Close()
		b.Close()
	}
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		copyPooled(b, a, onAtoB)
		once.Do(closeBoth)
	}()
	go func() {
		defer wg.Done()
		copyPooled(a, b, onBtoA)
		once.Do(closeBoth)
	}()
	wg.Wait()
}

func copyPooled(dst io.Writer, src io.Reader, onBytes func(int64)) {
	bufp := bufferPool.Get().(*[]byte)
	defer bufferPool.Put(bufp)
	n, _ := io.CopyBuffer(countingWriter{w: dst, cb: onBytes}, src, *bufp)
	if n > 0 && onBytes != nil {
		// countingWriter accounts successful Write calls; this keeps nil-safe symmetry.
	}
}

type countingWriter struct {
	w  io.Writer
	cb func(int64)
}

func (w countingWriter) Write(p []byte) (int, error) {
	n, err := w.w.Write(p)
	if n > 0 && w.cb != nil {
		w.cb(int64(n))
	}
	return n, err
}

func SetDeadline(c net.Conn, d time.Duration) {
	if d > 0 {
		_ = c.SetDeadline(time.Now().Add(d))
	}
}
