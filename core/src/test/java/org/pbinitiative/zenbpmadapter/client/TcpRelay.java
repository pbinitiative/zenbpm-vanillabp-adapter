/*
 * Copyright (c) 2026 Process Builders Initiative
 * SPDX-License-Identifier: MIT
 */
package org.pbinitiative.zenbpmadapter.client;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A TCP relay between the job stream and an engine: what the network between them would be. A
 * test cuts it to take the engine out of reach without stopping it, and restores it on the same
 * port, pointing at the same engine or at another one. It can also swallow everything without
 * closing anything, like a path a NAT or a load balancer dropped silently.
 */
final class TcpRelay implements AutoCloseable {

    private final ExecutorService threads = Executors.newCachedThreadPool();

    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();

    private final int port;

    private volatile ServerSocket listener;

    private volatile InetSocketAddress target;

    private volatile boolean swallowing;

    private TcpRelay(final InetSocketAddress target) {
        this.target = target;
        this.listener = listen(0);
        this.port = listener.getLocalPort();
        accept(listener);
    }

    /**
     * A relay which listens on a port the operating system picks.
     *
     * @param address <code>host:port</code> of the engine
     * @return The relay, relaying
     */
    static TcpRelay to(final String address) {
        return new TcpRelay(socketAddress(address));
    }

    /**
     * Where the job stream connects to.
     *
     * @return <code>host:port</code> of the relay
     */
    String address() {
        return "localhost:%d".formatted(port);
    }

    /**
     * Breaks every connection and refuses new ones, like a network which went away.
     */
    void cut() {
        close(listener);
        sockets.forEach(TcpRelay::close);
        sockets.clear();
    }

    /**
     * Keeps every connection open, the ones to come included, and passes nothing on, or passes
     * everything on again.
     *
     * @param swallow <code>true</code> to pass nothing on
     */
    void swallow(final boolean swallow) {
        swallowing = swallow;
    }

    /**
     * Takes connections again on the same port.
     *
     * @param address <code>host:port</code> of the engine to relay to from now on
     */
    void restoreTo(final String address) {
        target = socketAddress(address);
        listener = listen(port);
        accept(listener);
    }

    @Override
    public void close() {
        cut();
        threads.shutdownNow();
    }

    private static ServerSocket listen(final int port) {
        try {
            final var socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            return socket;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static InetSocketAddress socketAddress(final String address) {
        final var colon = address.lastIndexOf(':');
        return new InetSocketAddress(address.substring(0, colon), Integer.parseInt(address.substring(colon + 1)));
    }

    private static void close(final AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            // closed already
        }
    }

    private void accept(final ServerSocket accepting) {
        threads.execute(() -> {
            while (!accepting.isClosed()) {
                final Socket client;
                try {
                    client = accepting.accept();
                } catch (IOException e) {
                    // the relay was cut
                    continue;
                }
                if (swallowing) {
                    // held open, never answered
                    sockets.add(client);
                    continue;
                }
                try {
                    final var engine = new Socket(target.getHostString(), target.getPort());
                    sockets.add(client);
                    sockets.add(engine);
                    threads.execute(() -> pump(client, engine));
                    threads.execute(() -> pump(engine, client));
                } catch (IOException e) {
                    // the engine is not there, which the stream learns from a closed connection
                    close(client);
                }
            }
        });
    }

    private void pump(
        final Socket from,
        final Socket to
    ) {
        final var buffer = new byte[8192];
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (!swallowing) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
            }
        } catch (IOException e) {
            // one side went away
        } finally {
            close(from);
            close(to);
        }
    }

}
