package io.github.yromko.minesplat.inference;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.InvalidPathException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** User-facing installation advice; the original exception is retained in the log. */
public final class ModelInstallFeedback {
    private ModelInstallFeedback() {
    }

    static IOException insufficientSpace(Path directory, long required, long available) {
        String drive = directory.toAbsolutePath().getRoot().toString();
        return new ActionableFailure("Not enough disk space on " + drive
                + ". Need " + formatBytes(required) + ", available " + formatBytes(available)
                + ". Free up space or choose a model folder on another drive in Settings.");
    }

    static IOException httpFailure(int status, String file) {
        String advice = switch (status) {
            case 401, 403 -> "Access was denied. Check whether your network or proxy allows Hugging Face downloads.";
            case 404 -> "The model file was not found. Check for MineSplat updates or report this error.";
            case 429 -> "Too many requests. Wait a few minutes, then retry.";
            default -> status >= 500
                    ? "The model host is having trouble. Retry later."
                    : "The model host rejected the download. Retry or report this error.";
        };
        return new ActionableFailure("HTTP " + status + " for " + file + ". " + advice);
    }

    public static String describe(Throwable failure) {
        if (failure == null) {
            return null;
        }
        List<Throwable> causes = new ArrayList<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            causes.add(cause);
        }
        String advice = null;
        for (Throwable cause : causes) {
            if (cause instanceof ActionableFailure) {
                advice = cause.getMessage();
            } else if (cause instanceof AccessDeniedException) {
                advice = "Cannot access the model folder. Check its read/write permissions or choose another folder in Settings.";
            } else if (cause instanceof FileAlreadyExistsException
                    || cause instanceof NotDirectoryException || cause instanceof InvalidPathException) {
                advice = "The model folder path is invalid or points to a file. Choose a writable folder in Settings.";
            } else if (cause instanceof SSLException) {
                advice = "Cannot establish a secure connection to the model host. Check the system date, Java installation and HTTPS proxy or antivirus settings.";
            } else if (cause instanceof HttpTimeoutException) {
                advice = "The connection to the model host timed out. Check your internet connection or proxy, then retry.";
            } else if (cause instanceof UnknownHostException) {
                advice = "Cannot resolve the model host. Check your internet connection, DNS or proxy, then retry.";
            } else if (cause instanceof ConnectException) {
                advice = "Cannot connect to the model host. Check your internet connection, firewall or proxy, then retry.";
            }
            if (advice != null) {
                break;
            }
        }
        if (advice == null) {
            Throwable root = causes.get(causes.size() - 1);
            String message = root.getMessage();
            advice = root.getClass().getSimpleName();
            if (message != null && !message.isBlank()) {
                advice += ": " + message.replaceAll("\\s+", " ").trim();
            }
            if (advice.length() > 320) {
                advice = advice.substring(0, 317) + "...";
            }
        }
        return advice + "\nDetails: logs/latest.log.";
    }

    public static String formatBytes(long bytes) {
        if (bytes >= 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0);
        }
        if (bytes >= 1_000_000L) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0);
        }
        return bytes + " B";
    }

    private static final class ActionableFailure extends IOException {
        private ActionableFailure(String message) {
            super(message);
        }
    }
}
