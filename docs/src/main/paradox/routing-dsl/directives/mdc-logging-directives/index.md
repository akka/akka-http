# MdcLoggingDirectives

Directives for request-level mapped diagnostic context logging using the @apidoc[MarkerLoggingAdapter]. 

These directives provide an API to setup a new MDC-compatible logger for every request and append (key, value) MDC entries to be included in any emitted logs for the duration of the request 

For example, one might extract a request ID from a header at the beginning of the request and append it as a (key, value) MDC entry.
Any subsequent logs emitted by this request would include the request ID as an entry.

@@toc { depth=1 }

@@@ index

* [withMdcLogging](withMdcLogging.md)
* [withMdcEntries](withMdcEntries.md)
* [withMdcEntry](withMdcEntry.md)
* [extractMarkerLog](extractMarkerLog.md)

@@@

## Structured JSON MDC Logging

In order to get structured (i.e., JSON) MDC logging, some additional configurations are necessary. 
One possible configuration is as follows:

1. Add akka-slf4j, logback-classic, and logstash-logback-encoder as dependencies.
2. Add the configuration `akka.loggers = ["akka.event.slf4j.Slf4jLogger"]` to application.conf.
3. Create a `logback.xml` file with an appender that uses the LogstashEncoder:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder"/>
    </appender>
    <root level="INFO">
        <appender-ref ref="CONSOLE" />
    </root>
</configuration>
```  

