# Predefined Directives (by trait)

All predefined directives are organized into traits that form one part of the overarching `Directives` trait.

<a id="request-directives-java"></a>
## Directives filtering or extracting from the request

:ref:
*MethodDirectives-java*
: Filter and extract based on the request method.

:ref:
*HeaderDirectives-java*
: Filter and extract based on request headers.

:ref:
*PathDirectives-java*
: Filter and extract from the request URI path.

:ref:
*HostDirectives-java*
: Filter and extract based on the target host.

:ref:
*ParameterDirectives-java*
, :ref:
*FormFieldDirectives-java*
: Filter and extract based on query parameters or form fields.

:ref:
*CodingDirectives-java*
: Filter and decode compressed request content.

:ref:
*MarshallingDirectives-java*
: Extract the request entity.

:ref:
*SchemeDirectives-java*
: Filter and extract based on the request scheme.

:ref:
*SecurityDirectives-java*
: Handle authentication data from the request.

:ref:
*CookieDirectives-java*
: Filter and extract cookies.

:ref:
*BasicDirectives-java*
 and :ref:
*MiscDirectives-java*
: Directives handling request properties.

:ref:
*FileUploadDirectives-java*
: Handle file uploads.


<a id="response-directives-java"></a>
## Directives creating or transforming the response

:ref:
*CacheConditionDirectives-java*
: Support for conditional requests (`304 Not Modified` responses).

:ref:
*CookieDirectives-java*
: Set, modify, or delete cookies.

:ref:
*CodingDirectives-java*
: Compress responses.

:ref:
*FileAndResourceDirectives-java*
: Deliver responses from files and resources.

:ref:
*RangeDirectives-java*
: Support for range requests (`206 Partial Content` responses).

:ref:
*RespondWithDirectives-java*
: Change response properties.

:ref:
*RouteDirectives-java*
: Complete or reject a request with a response.

:ref:
*BasicDirectives-java*
 and :ref:
*MiscDirectives-java*
: Directives handling or transforming response properties.

:ref:
*TimeoutDirectives-java*
: Configure request timeouts and automatic timeout responses.


## List of predefined directives by trait

@@toc{ depth=1 }

@@@ index

* [basic-directives/index](basic-directives/index.md)
* [cache-condition-directives/index](cache-condition-directives/index.md)
* [coding-directives/index](coding-directives/index.md)
* [cookie-directives/index](cookie-directives/index.md)
* [debugging-directives/index](debugging-directives/index.md)
* [execution-directives/index](execution-directives/index.md)
* [file-and-resource-directives/index](file-and-resource-directives/index.md)
* [file-upload-directives/index](file-upload-directives/index.md)
* [form-field-directives/index](form-field-directives/index.md)
* [future-directives/index](future-directives/index.md)
* [header-directives/index](header-directives/index.md)
* [host-directives/index](host-directives/index.md)
* [marshalling-directives/index](marshalling-directives/index.md)
* [method-directives/index](method-directives/index.md)
* [misc-directives/index](misc-directives/index.md)
* [parameter-directives/index](parameter-directives/index.md)
* [path-directives/index](path-directives/index.md)
* [range-directives/index](range-directives/index.md)
* [respond-with-directives/index](respond-with-directives/index.md)
* [route-directives/index](route-directives/index.md)
* [scheme-directives/index](scheme-directives/index.md)
* [security-directives/index](security-directives/index.md)
* [websocket-directives/index](websocket-directives/index.md)
* [timeout-directives/index](timeout-directives/index.md)

@@@