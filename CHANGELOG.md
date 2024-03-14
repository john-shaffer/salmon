## Unreleased

- Add logging messages when creating, updating, or deleting a stack, and when waiting for a stack to enter a new state.
- (breaking) When a CloudFormation stack fails during creation, it enters the ROLLBACK_COMPLETE status. Previously, salmon would fail during stack start when the named stack is in ROLLBACK_COMPLETE status. Now salmon deletes the stack and creates a new stack with the same name.

## v0.15.0 (2024-02-24)

- Remove private `salmon.construct` code. This code has been moved to the [salmon.construct](https://github.com/john-shaffer/salmon.construct) repo.
  - Remove `io.staticweb/cloudformation-templating` dependency.
- Fix `salmon.test/bucket-name-schema` should prohibit dashes next to periods.
- (breaking) Throw an exception if a stack enters a rollback status when updating it. This condition previously did not throw and behaved the same as a successful update.
- Cloudformation stack delete will now wait on the stack to exit IN_PROGRESS states before attempting to delete. Previously, the delete would fail.
- (breaking) Remove use of ->error, ->info, and ->validate since donut.system has removed them. Replace with clojure.tools.logging and exceptions.
  - Add `clojure.tools.logging` as a direct dependency.
- (breaking) Remove salmon.signal/signal!, delete!, early-validate!, start!, and start!. They are no longer needed due to the removal of ->error, ->info, and ->validate.
- Fix an issue where the `:lint?` stack option was ignored.

## v0.14.0 (2024-01-16)

- (breaking) Rename `salmon.cleanup/delete-all!` to `delete-all-stacks!` and add required argument `:regions`.
- Add `salmon.resource.certificate/dns-validated`. This creates a
  resource that defines an automatically-validated AWS Certificate Manager
  (ACM) certificate.
- Add `salmon.construct.static-site.simple`. This provides a single
  high-performance static website backed by S3 and CloudFront. Note: This is not stable and can only be accessed via a private var.
  - Add `io.staticweb/cloudformation-templating` as a dependency.
- When a CloudFormation stack is in an `IN_PROGRESS` state and a start or
  delete signal is attempted, stacks would previously return an error.
  They now wait for a `COMPLETE` state before trying to apply the signal again.
- Add `salmon.util` functions `->ex-info` and `invoke!` for calls to the
  AWS API that should throw exceptions on failure.
- Fix pagination of stack resources not working.
- Fix `salmon.util` functions `aws-error-code` and `aws-error-message` parsing of
  errors found in `(-> response :Response :Errors)` rather than
  `(-> response :ErrorResponse)`.
- Add `salmon.ec2/list-orphaned-snapshots` to list snapshots that can
  safely be deleted.
    - Add `com.cognitect.aws/ec2` as a dependency.
- Add `salmon.uberjar` namespace for building deployable uberjars.
    - Add `io.github.clojure/tools.build` as a dependency.
- Add `salmon.ssm` namespace for AWS Systems Manager operations. This is currently used to fetch the Debian AMI IDs used in tests.
    - Add `com.cognitect.aws/ssm` as a dependency.
- Add `salmon.packer` namespace for building AMIs.
    - Require Clojure version 1.12.0-alpha5 so that the new `clojure.java.process` namespace can be used. This is preferable to adding a new dependency.
    - Add `org.clojure/data.csv` as a dependency.

## v0.13.0 (2023-11-04)

- Add `salmon.util` functions `full-name`, `tags`, and `resource`.
  These are useful when building CloudFormation templates.
- Add `:tags` option to stack for defining stack-level tags.
- Add `:tags` and `:tags-raw` maps to stack and stack-properties
  instances.
- Upgrade to `party.donut/system` 0.0.218.
- Fix a case where hosted zone "matches" returned by AWS don't
  necessarily match the query.
- Add `salmon.s3/upload!` for uploading to a bucket.
  - Add `com.cognitect.aws/s3` as a dependency.

## v0.12.0 (2023-11-01)

- Add `:client` and `:region` options to stack and stack-properties
  components.
- Move `anomaly?`, `aws-error-code`, and `aws-message-fns` to `salmon.util`
  and make them public.
- Add `salmon.route53/fetch-hosted-zone-id` as a simple way to look up
  which hosted zone id to attach RecordSets and RecordSetGroups to.
  - Add `com.cognitect.aws/route53` as a dependency.
- Upgrade to `party.donut/system` 0.0.215.

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
