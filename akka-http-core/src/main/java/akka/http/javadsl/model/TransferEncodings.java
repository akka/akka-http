/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model;

public final class TransferEncodings {
    private TransferEncodings() {}

    public static final TransferEncoding CHUNKED  = akka.http.scaladsl.model.TransferEncodings.chunked$.MODULE$;
    public static final TransferEncoding COMPRESS = akka.http.scaladsl.model.TransferEncodings.compress$.MODULE$;
    public static final TransferEncoding DEFLATE  = akka.http.scaladsl.model.TransferEncodings.deflate$.MODULE$;
    public static final TransferEncoding GZIP     = akka.http.scaladsl.model.TransferEncodings.gzip$.MODULE$;
    public static final TransferEncoding TRAILERS = akka.http.scaladsl.model.TransferEncodings.trailers$.MODULE$;
}
