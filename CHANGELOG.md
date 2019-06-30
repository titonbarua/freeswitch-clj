# Change Log

## [Unreleased]

## [0.2.2] - 2019-06-30

### Changed:
- Test and doc dependencies are moved to seperate profiles.
- Test is now configurable with `FS_HOST`, `FST_PORT` and `FS_PASS` environment variables.
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

[0.2.1]: https://github.com/titonbarua/freeswitch-clj/compare/v0.2.0...v0.2.1
[0.2.0]: https://github.com/titonbarua/freeswitch-clj/compare/v0.1.0...v0.2.0
[unreleased]: https://github.com/titonbarua/freeswitch-clj/compare/v0.1.0...HEAD
