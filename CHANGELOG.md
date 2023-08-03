## Unreleased

- Add `:client` and `:region` options to stack and stack-properties
  components.
- Move `anomaly?`, `aws-error-code`, and `aws-message-fns` to `salmon.util`
  and make them public.
- Add `salmon.route53/fetch-hosted-zone-id` as a simple way to look up
  which hosted zone id to attach RecordSets and RecordSetGroups to.
  - Add com.cognitect.aws/route53 as a dependency.

## v0.11.0 (2023-08-01)

- (breaking) Change the format of a stack's `:resources` from a seq to a map with
  each resource's LogicalResourceId as keys. This allows individual resources to be
  the target of donut.system refs.
  (e.g., `(ds/local-ref [:MyStack :resources :MyBucket :PhysicalResourceId])`)
- Handle custom resolution fns via `:donut.system/resolve-refs`. Custom
  resolution fns will prevent early validation.

## v0.10.1 (2023-07-29)

- Fix incorrect resolution of local-refs (again).

## v0.10.0 (2023-07-28)

- Fix incorrect resolution of local-refs.
- (breaking) `salmon.validation/resolve-refs` now takes a 
  `referencing-component-id` argument so that it can handle local-refs. 
- Add `:parameters` and `:parameters-raw` maps to stack and stack-properties
  instances.
- Add `:describe-stack-raw` to stack and stack-properties instances. This provides
  access to the raw DescribeStack response.
- Fix missing part of error message in `salmon.signal` fns.

## v0.9.0 (2023-07-08)

- Fix a bug in salmon.signal/early-validate-conf that caused it to fail
  early-schema validation with newer versions of donut.system.
- Update donut.system dependency from club.donutpower/system 0.0.165
  to party.donut/system 0.0.203.
- Handle non-string error and validation messages in salmon.signal/signal!
- Show a humanized error message for early-schema validation errors
- Allow donut.system local-refs in component configs
- (breaking) `salmon.validation/refs-resolveable?` now takes a `component-id` argument so that it can handle local-refs. 
- Add `:name` to stack instance.
- Add salmon.cloudformation/stack-properties to create a component that
  retrieves the properties of an existing CloudFormation stack. Properties
  include resources and outputs.
