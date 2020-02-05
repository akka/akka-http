package akka.http.javadsl.model;

import akka.annotation.DoNotInherit;

@DoNotInherit
public interface AttributeKey<T> {
    static <U> AttributeKey<U> newInstance() {
        return new akka.http.scaladsl.model.AttributeKey<U>();
    }
}
