/**
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fabric8.kubernetes.client.dsl.internal;

import com.squareup.okhttp.Response;
import com.squareup.okhttp.ws.WebSocket;
import com.squareup.okhttp.ws.WebSocketListener;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import okio.Buffer;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public class ExecWebSocketListener implements ExecWatch, WebSocketListener, AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(ExecWebSocketListener.class);

    private final InputStream in;
    private final OutputStream out;
    private final OutputStream err;

    private final PipedOutputStream input;
    private final PipedInputStream output;
    private final PipedInputStream error;

    private final AtomicReference<WebSocket> webSocketcRef = new AtomicReference<>();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final InputStreamPumper pumper = new InputStreamPumper();

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final ArrayBlockingQueue<Object> queue = new ArrayBlockingQueue<>(1);

    public ExecWebSocketListener(InputStream in, OutputStream out, OutputStream err, PipedOutputStream inputPipe, PipedInputStream outputPipe, PipedInputStream errorPipe) {
        this.in = inputStreamOrPipe(in, inputPipe);
        this.out = outputStreamOrPipe(out, outputPipe);
        this.err = outputStreamOrPipe(err, errorPipe);

        this.input = inputPipe;
        this.output = outputPipe;
        this.error = errorPipe;
    }

    @Override
    public void close() {
        pumper.close();
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (Throwable t) {
            throw KubernetesClientException.launderThrowable(t);
        }
    }

    public void waitUntiReady() {
        try {
            Object obj = queue.poll(10, TimeUnit.SECONDS);
            if (obj instanceof Boolean && ((Boolean) obj)) {
                return;
            } else {
                if (obj instanceof Throwable) {
                    throw (Throwable) obj;
                }
            }
        } catch (Throwable t) {
            throw KubernetesClientException.launderThrowable(t);
        }
    }

    @Override
    public void onOpen(WebSocket webSocket, Response response) {
        try {
            if (in instanceof PipedInputStream && input != null) {
                input.connect((PipedInputStream) in);
            }
            if (out instanceof PipedOutputStream && output != null) {
                output.connect((PipedOutputStream) out);
            }
            if (err instanceof PipedOutputStream && error != null) {
                error.connect((PipedOutputStream) err);
            }

            webSocketcRef.set(webSocket);
            executorService.submit(pumper);
            started.set(true);
            queue.add(true);
        } catch (IOException e) {
            queue.add(e);
        }
    }

    @Override
    public void onFailure(IOException ioe, Response response) {
        LOGGER.error(response != null ? response.message() : "Exec Failure.", ioe);
        //We only need to queue startup failures.
        if (!started.get()) {
            queue.add(ioe);
        }
    }

    @Override
    public void onMessage(BufferedSource bufferedSource, WebSocket.PayloadType payloadType) throws IOException {
        try {
            byte streamID = bufferedSource.readByte();
            ByteString byteString = bufferedSource.readByteString();
            if (byteString.size() > 0) {
                switch (streamID) {
                    case 1:
                        if (out != null) {
                            out.write(byteString.toByteArray());
                        }
                        break;
                    case 2:
                        if (err != null) {
                            err.write(byteString.toByteArray());
                        }
                        break;
                    case 3:
                        if (err != null) {
                            err.write(byteString.toByteArray());
                        }
                        break;
                    default:
                        throw new IOException("Unknown stream ID " + streamID);
                }
            }
        } finally {
            bufferedSource.close();
        }
    }

    @Override
    public void onPong(Buffer buffer) {
        LOGGER.debug("Exec Web Socket: On Pong");
    }

    @Override
    public void onClose(int i, String s) {
        LOGGER.debug("Exec Web Socket: On Close");
    }

    public OutputStream getInput() {
        return input;
    }

    public InputStream getOutput() {
        return output;
    }

    public InputStream getError() {
        return error;
    }

    private void send(byte[] bytes) throws IOException {
        if (bytes.length > 0) {
            WebSocket ws = webSocketcRef.get();
            if (ws != null) {

                try (BufferedSink sink = ws.newMessageSink(WebSocket.PayloadType.BINARY)) {
                    sink.write(new byte[]{0});
                    sink.write(bytes);
                    sink.flush();
                }
            }
        }
    }

    private void send(String msg) throws IOException {
        send(msg.getBytes());
    }

    private static InputStream inputStreamOrPipe(InputStream stream, PipedOutputStream out) {
        if (stream != null) {
            return stream;
        } else if (out != null) {
            return new PipedInputStream();
        } else {
            return null;
        }
    }

    private static OutputStream outputStreamOrPipe(OutputStream stream, PipedInputStream in) {
        if (stream != null) {
            return stream;
        } else if (in != null) {
            return new PipedOutputStream();
        } else {
            return null;
        }
    }

    private class InputStreamPumper implements Runnable, Closeable {

        private boolean keepReading = true;
        @Override
        public void run() {
            try (InputStreamReader reader = new InputStreamReader(in);
                 BufferedReader bufferedReader = new BufferedReader(reader)) {
                String line;
                while (keepReading && !Thread.currentThread().isInterrupted() && (line = bufferedReader.readLine()) != null) {
                    send(line + "\n");
                }
            } catch (IOException e) {
                if (keepReading) {
                    LOGGER.error("Error while pumping stream.", e);
                }
            }
        }

        public void close() {
            keepReading = false;
        }
    }
 }
