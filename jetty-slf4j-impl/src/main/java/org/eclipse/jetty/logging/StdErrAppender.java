//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.logging;

import java.io.PrintStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.TimeZone;

import org.slf4j.event.Level;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;
import org.slf4j.helpers.NormalizedParameters;

public class StdErrAppender implements JettyAppender
{
    private static final Object[] EMPTY_ARGS = new Object[0];
    private final DateTimeFormatter timestampFormatter;
    private final ZoneId timezone;
    /**
     * True to have output show condensed logger names, false to use the as defined long names.
     */
    private final boolean condensedNames;
    /**
     * True to have messages escaped for control characters, false to leave messages alone.
     */
    private final boolean escapedMessages;
    /**
     * True to have formatting be based on the strict definition of Slf4J's {@link MessageFormatter},
     * where there has to be a match to the number of <code>{}</code> in the format string
     * to the number of arguments provided on the various {@link org.slf4j.Logger} methods.
     * False will use the old-school Jetty message formatter, which will add missing <code>{}</code>
     * entries to the end of the format String if it detects more arguments then there are <code>{}</code>
     * elements in the provided format String.
     */
    private final boolean strictFormat;
    /**
     * The stream to write logging events to.
     */
    private final PrintStream stderr;

    public StdErrAppender(JettyLoggerConfiguration config)
    {
        this(config, System.err);
    }

    public StdErrAppender(JettyLoggerConfiguration config, PrintStream stream)
    {
        this(config, stream, TimeZone.getDefault().toZoneId());
    }

    public StdErrAppender(JettyLoggerConfiguration config, PrintStream stream, ZoneId zoneId)
    {
        Objects.requireNonNull(config, "JettyLoggerConfiguration");
        this.stderr = Objects.requireNonNull(stream, "PrintStream");
        this.timezone = zoneId;
        this.timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
        this.timestampFormatter.withZone(timezone);

        this.condensedNames = config.isNameCondense();
        this.escapedMessages = config.isEscapeMessages();
        this.strictFormat = config.isStrictFormatSyntax();
    }

    @Override
    public void emit(JettyLogger logger, Level level, long timestamp, String threadName, String message)
    {
        emit(logger, level, timestamp, threadName, null, message, EMPTY_ARGS);
    }

    @Override
    public void emit(JettyLogger logger, Level level, long timestamp, String threadName, Throwable throwable, String message)
    {
        emit(logger, level, timestamp, threadName, throwable, message, EMPTY_ARGS);
    }

    @Override
    public void emit(JettyLogger logger, Level level, long timestamp, String threadName, String message, Object... argumentArray)
    {
        Throwable cause = NormalizedParameters.getThrowableCandidate(argumentArray);
        emit(logger, level, timestamp, threadName, cause, message, argumentArray);
    }

    @Override
    public void emit(JettyLogger logger, Level level, long timestamp, String threadName, Throwable throwable, String message, Object... argumentArray)
    {
        StringBuilder builder = new StringBuilder(64);
        format(builder, logger, level, timestamp, threadName, throwable, message, argumentArray);
        stderr.println(builder);
    }

    private void format(StringBuilder builder, JettyLogger logger, Level level, long timestamp, String threadName, Throwable throwable, String message, Object... argumentArray)
    {
        Throwable cause = throwable;

        // Timestamp
        ZonedDateTime tsInstant = Instant.ofEpochMilli(timestamp).atZone(timezone);
        timestampFormatter.formatTo(tsInstant, builder);

        // Level
        builder.append(':').append(renderedLevel(level));

        // Logger Name
        builder.append(':');
        if (condensedNames)
        {
            builder.append(logger.getCondensedName());
        }
        else
        {
            builder.append(logger.getName());
        }

        // Thread Name
        builder.append(':');
        builder.append(threadName); // TODO: support TAG_PAD configuration
        builder.append(':');

        // Message
        builder.append(' ');

        if (strictFormat)
        {
            FormattingTuple ft = MessageFormatter.arrayFormat(message, argumentArray);
            appendEscaped(builder, ft.getMessage());
            if (cause == null)
            {
                cause = ft.getThrowable();
            }
        }
        else
        {
            // TODO: this should really be removed, as it violates the slf4j API contract for throwables and such
            StringBuilder msg = new StringBuilder();
            Object[] args = argumentArray == null ? EMPTY_ARGS : argumentArray;
            msg.append(Objects.requireNonNullElseGet(message, () -> "{} ".repeat(args.length)));
            String braces = "{}";
            int start = 0;
            for (Object arg : args)
            {
                int bracesIndex = msg.indexOf(braces, start);
                if (bracesIndex < 0)
                {
                    appendEscaped(builder, msg.substring(start));
                    builder.append(" ");
                    if (arg != null)
                        builder.append(arg);
                    start = msg.length();
                }
                else
                {
                    appendEscaped(builder, msg.substring(start, bracesIndex));
                    builder.append(arg);
                    start = bracesIndex + braces.length();
                }
            }
            appendEscaped(builder, msg.substring(start));
        }

        // Throwable
        if (cause != null)
        {
            if (logger.isHideStacks())
            {
                builder.append(": ").append(cause);
            }
            else
            {
                appendCause(builder, cause, "");
            }
        }
    }

    private String renderedLevel(Level level)
    {
        switch (level)
        {
            case ERROR:  // New for Jetty 10+
                return "ERROR";
            case WARN:
                return "WARN";
            case INFO:
                return "INFO";
            case DEBUG:
                return "DBUG"; // keeping abbreviated name for historical reasons
            case TRACE: // New for Jetty 10+
                return "TRACE";
            default:
                return "UNKNOWN";
        }
    }

    private void appendCause(StringBuilder builder, Throwable cause, String indent)
    {
        builder.append(System.lineSeparator()).append(indent);
        appendEscaped(builder, cause.toString());
        StackTraceElement[] elements = cause.getStackTrace();
        for (int i = 0; elements != null && i < elements.length; i++)
        {
            builder.append(System.lineSeparator()).append(indent).append("\tat ");
            appendEscaped(builder, elements[i].toString());
        }

        for (Throwable suppressed : cause.getSuppressed())
        {
            builder.append(System.lineSeparator()).append(indent).append("Suppressed: ");
            appendCause(builder, suppressed, "\t|" + indent);
        }

        Throwable by = cause.getCause();
        if (by != null && by != cause)
        {
            builder.append(System.lineSeparator()).append(indent).append("Caused by: ");
            appendCause(builder, by, indent);
        }
    }

    private void appendEscaped(StringBuilder builder, String str)
    {
        if (str == null)
            return;

        if (escapedMessages)
        {
            for (int i = 0; i < str.length(); ++i)
            {
                char c = str.charAt(i);
                if (Character.isISOControl(c))
                {
                    if (c == '\n')
                    {
                        builder.append('|');
                    }
                    else if (c == '\r')
                    {
                        builder.append('<');
                    }
                    else
                    {
                        builder.append('?');
                    }
                }
                else
                {
                    builder.append(c);
                }
            }
        }
        else
            builder.append(str);
    }
}