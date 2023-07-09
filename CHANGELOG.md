## Unreleased

- Fix a bug in salmon.signal/early-validate-conf that caused it to fail
  early-schema validation with newer versions of donut.system.
- Update donut.system dependency from club.donutpower/system 0.0.165
  to party.donut/system 0.0.171.
- Handle non-string error and validation messages in salmon.signal/signal!
- Show a humanized error message for early-schema validation errors
- Allow donut.system local-refs in component configs
- (breaking) `salmon.validation/refs-resolveable?` now takes a `component-id` argument so that it can handle local-refs. 
- Add `:name` to stack instance.
- Add salmon.cloudformation/stack-properties to create a component that
  retrieves the properties of an existing CloudFormation stack. Properties
  include resources and outputs.
