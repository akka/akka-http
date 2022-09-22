# Migration Guide to and within Akka HTTP 10.4.x

The license for using Akka HTTP in production has been changed to Business Source License v1.1.
[Why We Are Changing the License for Akka](https://www.lightbend.com/blog/why-we-are-changing-the-license-for-akka)
explains the reasons and a [detailed FAQ](https://www.lightbend.com/akka/license-faq) is available to answer many of
the questions that you may have about the license change.

## General Notes

See the general @ref[compatibility guidelines](../compatibility-guidelines.md).

Under these guidelines, minor version updates are supposed to be binary compatible and drop-in replacements
for former versions under the condition that user code only uses public, stable, non-deprecated API. Especially
libraries should make sure not to depend on deprecated API to be compatible with both 10.2.x and 10.4.x.

If you find an unexpected incompatibility please let us know, so we can check whether the incompatibility is accidental,
so we might still be able to fix it.

No configuration changes are needed for updating an application from Akka HTTP 10.2.x to 10.4.x.

No deprecated features or APIs have been removed in Akka HTTP 10.4.x.

## Dependency updates

### Akka

Akka HTTP 10.4.x requires Akka version >= 2.7.0.

### Jackson

The Jackson dependency has been updated to 2.13.4 in Akka HTTP 10.4.0. That bump includes many fixes and changes to
Jackson, but it should not introduce any incompatibility in serialized format.
