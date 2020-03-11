# Migration Guide to and within Akka HTTP 10.2.x

## General Notes

See the general @ref[compatibility guidelines](../compatibility-guidelines.md).

## Akka HTTP 10.1.11 - > 10.2.0

### Strict query strings

In 10.1.x, parsing the query string of URL's accepted characters that are
strictly speaking not allowed, even when `parsing.uri-parsing-mode` was
set to the default value of `strict`. If you want to allow such characters
in incoming URI's, set `parsing.uri-parsing-mode` to `relaxed`