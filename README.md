# url-redirector

A Clojure application designed to manage URL redirection using data from a JSON document stored in MongoDB and cached in REDIS.

*IMPORTANT*: By default the application has a maximum response time of 500ms, after which it will return a HTTP status 202

### Response: HTTP 301

The library maps the original domain, transforms as appropriate and returns the new URL via HTTP 301

The meaning of HTTP 301 is Moved Permanently. The semantics of this must fit your needs if you wish to use this library.

The detailed semantics of HTTP 301 is explained in [more detail on Wikipedia](https://en.wikipedia.org/wiki/HTTP_301)

Specifically, 301 is recommended by Google to change the URL of a page as it is shown in search engine results.

## What redirections are supported?

#### Original Domain -> New Domain redirection

foo.com -> bar.com

TBD... paste in example output

#### Original Domain -> New Domain + path redirection

foo.com -> bar.com/place/index.html

TBD... paste in example output

## Usage

A JSON document is used to define the transformations that are required. The document requires two objects with 
the same structure: source-domain and target-domain. The table lists the supported properties:

| Property      | Type    | Values          | Required?  | Default Value |
| --------      | ------- | --------------- | --------   | ------------- |
| scheme        | string  | http or https   | No         | "http"        |
| domain        | string  | domain name     | Yes        | None          |
| port          | number  | valid HTTP/S port | No       | None          |
| path          | string  | resource path     | No       | None          |

## Limitations

In the existing design, no attempt is made to validate the transformed URLs.

## Notes for PAAS platforms (for example Heroku)

### Heroku

The source domain must be added to the list of domains supported by the application. 

This function is *NOT* provided by this application.

## Dependencies

You need to install locally or have network services that provide MongoDB and REDIS.

The application will attach to these services using the following variables / defaults
 
| Name          | Default Value |
| --------      | ------------- |
| MONGO_URL     | mongodb://localhost/test |
| REDIS_URL     | 127.0.0.1:6379 |

## Options

You can tweak the application behaviour with a small number of options

| Name          | Meaning          | Default Value |
| --------      | -------          | ------------- |
| PORT | Port # for exposed HTTP endpoint | 5000 |
| MONGO_COLLECTION  | Document collection name | redirections |
| REDIS_TTL_SECONDS | # Seconds REDIS caches values | 30 |
| SLA_MILLISECONDS | # Milliseconds before SLA fails (HTTP 202 response) | 500 |

###### Example 1: Domain redirection

```JavaScript
{
  "source-domain": {
    "domain": "foo.com"
  },
  "target-domain": {
    "domain": "bar.com"
  }
}
```

###### Example 2: Domain + path redirection

```JavaScript
{
  "source-domain": {
    "domain": "foo.com"
  },
  "target-domain": {
    "domain": "bar.com",
    "path": "/new/path"
  }
}
```

###### Example 3: All options in use

```JavaScript
{
  "source-domain": {
    "scheme" : "http",
    "domain": "foo.com",
    "port": 8080,
    "path": "/old/path"
  },
  "target-domain": {
    "scheme" : "https",
    "domain": "bar.com",
    "port": 553,
    "path": "/new/path"
  }
}
```

## Testing

The application comes with a number of pre-built tests to ensure that the general logic is valid.

To confirm, you can run inspect the tests and run them with 'lein test'

Please extend the test suite with your specific needs.

## Pull requests

I will accept pull requests for the core logic and especially for any new tests.

## License

Copyright © 2014 Opengrail BVBA

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.