/*
 * Copyright (C) 2009-2025 Lightbend Inc. <https://akka.io>
 */

package akka.http.javadsl.model.headers;

public interface LanguageRange {
    public abstract String primaryTag();
    public abstract float qValue();
    public abstract boolean matches(Language language);
    public abstract Iterable<String> getSubTags();
    public abstract LanguageRange withQValue(float qValue);
}
