package gen5w.svm.diagnostics;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

final class Commands {
    static final int LIMIT = 64 * 1024;

    enum Status { OK, PERMISSION_DENIED, UNSUPPORTED, TIMEOUT, FAILED, TRUNCATED, CANCELLED, MALFORMED }

    interface Runner {
        Result run(String... arguments);
    }

    static final class Result {
        final Status status;
        final String output;

        Result(Status status, String output) {
            this.status = status;
            this.output = output;
        }

        static Result from(int exit, String output, boolean truncated) {
            String lower = output.toLowerCase(Locale.US);
            if (lower.contains("permission denied") || lower.contains("permission denial")
                    || lower.contains("operation not permitted") || lower.contains("securityexception")) {
                return new Result(Status.PERMISSION_DENIED, "");
            }
            if (exit == 127 || (exit != 0 && (lower.contains("not found")
                    || lower.contains("unknown option") || lower.contains("invalid option")))) {
                return new Result(Status.UNSUPPORTED, "");
            }
            if (exit != 0) return new Result(Status.FAILED, "");
            if (truncated) return new Result(Status.TRUNCATED, "");
            return new Result(Status.OK, output);
        }
    }

    static final class Local implements Runner {
        private final long timeoutMs;

        Local(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        @Override public Result run(String... arguments) {
            if (Thread.currentThread().isInterrupted()) return new Result(Status.CANCELLED, "");
            final Process child;
            try {
                child = new ProcessBuilder(arguments).redirectErrorStream(true).start();
            } catch (IOException e) {
                String message = String.valueOf(e.getMessage()).toLowerCase(Locale.US);
                Status status = message.contains("permission denied") || message.contains("error=13")
                        ? Status.PERMISSION_DENIED
                        : (message.contains("not found") || message.contains("error=2")
                            || message.contains("no such file") ? Status.UNSUPPORTED : Status.FAILED);
                return new Result(status, "");
            } catch (SecurityException e) {
                return new Result(Status.PERMISSION_DENIED, "");
            }

            final Result[] result = new Result[1];
            Thread reader = new Thread(new Runnable() {
                @Override public void run() {
                    try {
                        InputStream stream = child.getInputStream();
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        byte[] block = new byte[2048];
                        boolean truncated = false;
                        int count;
                        while ((count = stream.read(block)) != -1) {
                            int accepted = Math.min(count, LIMIT - output.size());
                            output.write(block, 0, accepted);
                            truncated |= accepted < count;
                        }
                        result[0] = Result.from(child.waitFor(), output.toString("UTF-8"), truncated);
                    } catch (IOException e) {
                        result[0] = new Result(Status.FAILED, "");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }, "diagnostic-command");
            reader.setDaemon(true);
            reader.start();
            try {
                reader.join(timeoutMs);
                return reader.isAlive() ? new Result(Status.TIMEOUT, "")
                        : result[0] == null ? new Result(Status.FAILED, "") : result[0];
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new Result(Status.CANCELLED, "");
            } finally {
                child.destroy();
                reader.interrupt();
            }
        }
    }

    private Commands() {}
}
