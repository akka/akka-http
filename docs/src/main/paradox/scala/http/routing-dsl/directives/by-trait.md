# Predefined Directives (by trait)

All predefined directives are organized into traits that form one part of the overarching `Directives` trait.

<a id="request-directives"></a>
## Directives filtering or extracting from the request

:ref:
*MethodDirectives*
: Filter and extract based on the request method.

:ref:
*HeaderDirectives*
: Filter and extract based on request headers.

:ref:
*PathDirectives*
: Filter and extract from the request URI path.

:ref:
*HostDirectives*
: Filter and extract based on the target host.

:ref:
*ParameterDirectives*
, :ref:
*FormFieldDirectives*
: Filter and extract based on query parameters or form fields.

:ref:
*CodingDirectives*
: Filter and decode compressed request content.

:ref:
*MarshallingDirectives*
: Extract the request entity.

:ref:
*SchemeDirectives*
: Filter and extract based on the request scheme.

:ref:
*SecurityDirectives*
: Handle authentication data from the request.

:ref:
*CookieDirectives*
: Filter and extract cookies.

:ref:
*BasicDirectives*
 and :ref:
*MiscDirectives*
: Directives handling request properties.

:ref:
*FileUploadDirectives*
: Handle file uploads.


<a id="response-directives"></a>
## Directives creating or transforming the response

:ref:
*CacheConditionDirectives*
: Support for conditional requests (`304 Not Modified` responses).

:ref:
*CookieDirectives*
: Set, modify, or delete cookies.

:ref:
*CodingDirectives*
: Compress responses.

:ref:
*FileAndResourceDirectives*
: Deliver responses from files and resources.

:ref:
*RangeDirectives*
: Support for range requests (`206 Partial Content` responses).

:ref:
*RespondWithDirectives*
: Change response properties.

:ref:
*RouteDirectives*
: Complete or reject a request with a response.

:ref:
*BasicDirectives*
 and :ref:
*MiscDirectives*
: Directives handling or transforming response properties.

:ref:
*TimeoutDirectives*
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