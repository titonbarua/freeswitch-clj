# Change Log

## [Unreleased]

## [1.4.0] - 2023-18-06
- Upgraded most dependencies. Minimum required clojure version is now 1.10.x
  due to requirement of aleph 0.8.0.


## [1.3.0-SNAPSHOT-4] - 2021-12-29
- **Breaking** Most blocking api functions now take an optional keyword argument
  named `resp-timeout`. Previously, they waited forever for server response, causing
  an occasional thread leak in busy situations. The default value is 30 seconds.
- Most public functions are now wrapped in a macro which closes the connection in case
  of exception and re-throws the exception.
- Exceptions inside event handlers are now ignored and does not break the event dispatcher
  loop.
- Exceptions inside connection handler in freeswitch outbound server mode now closes
  the connection.
- Outgoing aleph stream is now closed during connection closure.

## [1.2.2] - 2021-11-20
- Fixed potential memory/thread leak issue.

## [1.2.1] - 2021-11-13
### Changed
- Fixed response channels being dangled after a connection is drained.
  They are now closed after drainage and returns nil on read.
### Added
- Added a new function `clear-all-event-handlers`
  to help clear event handlers without destroying it's sorted type.

## [1.2.0] - 2021-11-03
### changed
- Changed logging framework from `timbre` to `clojure.tools.logging`.
- Changed json library from `cheshire` to `jsonista`.
- Added an atom based flag - `freeswitch-clj.core/warn-on-handler-less-event?`
  which can be toggled to turn off warnings on handler less event.
- Improved event dispatch performance. This is noticeable when
  a very large number of handlers are attached to a single connection.

## [1.1.2] - 2021-02-23
### Changed
- Fixed various edge cases related to connection closure -
  - Fixed overzealous disconnect upon receiving disconnect notice.
  - Fixed `on-close` function being called multiple times.
  - Freeswitch-clj now works as intended with `linger` mode.
- Data reception is now buffered. The buffer can be configured
  using `incoming-buffer-size` parameter to `listen` and `connect`.
  This helps smooth out incoming data flow duing high load situations.
- Revamped the test environment using docker-compose. Also, added more
  tests to simulate the edge case behaviors.
- Freeswitch-clj now correctly handles `text/rude-rejection` message
  and throws an exception with a helpful message.

## [1.1.1] - 2021-02-09
### Changed
- `on-close` function has been changed to take a single argument -
   the freeswitch connection. Without that, distinguishing connections
   in freeswith-outbound mode becomes impossible.

## [1.1.0] - 2021-02-09
### Added
- Added a new optional parameter - `on-close` to both `listen` and
  `connect` functions. If given, this zero arity function will be
  called after connection to freeswitch is closed, just before
  `:closed?` promise is delivered. This can be useful in event driven
  designs.

## [1.0.3] - 2021-02-05
### Changed
- Fixed a concurrency related bug where pushing large amount of
  messages through same connection can make the responses be
  processed out of order and crash freeswitch-clj.

## [1.0.2] - 2021-01-29
### Changed
- Fixed a major concurrent access bug. Connections can now
  be safely accessed from different threads.

## [1.0.1] - 2021-01-29
### Changed
- Fixed a major bug where trying to use network disconnected
  connections were not raising errors and were hanging instead.
  Such situations now properly raise `IOException` .

## [1.0.0] - 2021-01-28

### Added
- Added a new parameter - `:pre-init-fn` to `listen` function.
  This can help avoid unpredictability of event handling in
  freeswitch-outbound mode.
- Added a new parameter - `:async-thread-type` to `listen` .
  This parameter determines the type of threads(2 in total) to
  spawn for event dispatch and handling. Valid values are -
  `:thread` and `:go-block`. By default, `:thread` is used.
  In previous versions, go-block was used for dispatch, while
  thread was used for handler execution.
- Added a new parameter - `:async-thread-type` to `connect`
  function. This is analogous to the new parameter for `listen.
- Added a docker based testing environment.
- Added test to check correct behavior of `listen` function,
  with and without `:pre-init-fn`.

## [0.2.3] - 2021-01-28

### Changed
- Handler execution and subsequent connection closing is now wrapped in a
  try-finally block so that handler crash does not keep the connection open.


## [0.2.2] - 2019-06-30

### Changed:
- Test and doc dependencies are moved to seperate profiles.
- Test is now configurable with `FS\_HOST`, `FS\_PORT` and `FS\_PASS` environment variables.
- Changed doc format to markdown.
- Changed documentation links from github pages to cljdoc.org.
- Updated project dependencies.
- Changed default log level to be `:warn` so that user application logs aren't bombarded with debug information,
  fixing issue #1.
- Fixed issue #4.

### Added:
- Added a call-origination example to tutorial.
- Added a way to pass custom `:event-uuid` to `req-call-execute` function.
- Added a `:conn-timeout` keyword argument to `connect` function.

### Removed:
- Deleted some unused requirements from namespace declaration.

## [0.2.1] - 2018-01-18

### Changed:

- Fixed '+' disappearing from phone numbers in event header values.
- Fixed channel-data header values not being decoded in outbound handler.

## [0.2.0] - 2017-11-18

### Changed:

- Event handling system has been rewritten using `core.async`.
- Event matching system has beeen generalized. Now events can be matched against arbitrary header values.
- `req-sendmsg` and `req-call-execute` has been thoroughly tested.
- Most of the function signatures involing events has been changed.

## 0.1.0 - 2017-11-14

Initial commit.

[1.1.2]: https://github.com/titonbarua/freeswitch-clj/compare/v1.1.1...v1.1.2
[1.1.1]: https://github.com/titonbarua/freeswitch-clj/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/titonbarua/freeswitch-clj/compare/v1.0.3...v1.1.0
[1.0.3]: https://github.com/titonbarua/freeswitch-clj/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/titonbarua/freeswitch-clj/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/titonbarua/freeswitch-clj/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/titonbarua/freeswitch-clj/compare/v0.2.3...v1.0.0
[0.2.3]: https://github.com/titonbarua/freeswitch-clj/compare/v0.2.2...v0.2.3
[0.2.2]: https://github.com/titonbarua/freeswitch-clj/compare/v0.2.1...v0.2.2
[0.2.1]: https://github.com/titonbarua/freeswitch-clj/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/titonbarua/freeswitch-clj/compare/v0.1.0...v0.2.0
[unreleased]: https://github.com/titonbarua/freeswitch-clj/compare/v0.1.0...HEAD
