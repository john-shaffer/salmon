(ns salmon.construct.static-site.wildcard
  (:require [clojure.java.io :as io]
            [cognitect.aws.client.api :as aws]
            [donut.system :as ds]
            [io.staticweb.cloudformation-templating :as ct]
            [medley.core :as me]
            [salmon.cloudformation :as cf]
            [salmon.resource.certificate :as r-cert]
            [salmon.route53 :as r53]
            [salmon.util :as u]))

(def ^:private aws-cloudfront-hosted-zone-id "Z2FDTNDATAQYW2")

;; logging component
;; logging buckets because cloudfront can't deliver
;; to some regions

;; https://docs.aws.amazon.com/AmazonCloudFront/latest/DeveloperGuide/AccessLogs.html
;; Note that some regions don't work for CloudFront logs
(defn- logging-bucket [& {:as opts :keys [tags]}]
  (u/resource
    "AWS::S3::Bucket"
    {:BucketEncryption
     {:ServerSideEncryptionConfiguration
      [{:ServerSideEncryptionByDefault
        {:SSEAlgorithm "AES256"}}]}
     :LifecycleConfiguration
     {:Rules
      [{:ExpirationInDays 180
        :Status "Enabled"
        :Transitions
        [{:StorageClass "GLACIER"
          :TransitionInDays 7}]}]}
     :OwnershipControls
     {:Rules
      [{:ObjectOwnership "BucketOwnerPreferred"}]}
     :Tags (u/tags tags)}
    opts))

(defn- logging-bucket-policy [logging-bucket & {:as opts}]
  (u/resource
    "AWS::S3::BucketPolicy"
    {:Bucket logging-bucket
     :PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Action "s3:PutObject"
        :Condition {:StringEquals {"s3:x-amz-acl" "bucket-owner-full-control"}}
        :Effect "Allow"
        :Principal {:Service "logs.amazonaws.com"}
        :Resource (ct/join "" ["arn:aws:s3:::" logging-bucket "/*"])}]}}
    opts))

(defn- static-site-bucket [& {:as opts :keys [tags]}]
  (u/resource
    "AWS::S3::Bucket"
    {:PublicAccessBlockConfiguration
     {:BlockPublicAcls true}
     :Tags (u/tags tags)
     :WebsiteConfiguration
     {:IndexDocument "index.html"
      :ErrorDocument "error.html"}}
    opts))

(defn- static-site-bucket-policy
  [bucket origin-access-identity
   & {:as opts}]
  (u/resource
    "AWS::S3::BucketPolicy"
    {:Bucket bucket
     :PolicyDocument
     {:Version "2012-10-17"
      :Statement
      [{:Effect "Allow"
        :Principal
        {:CanonicalUser
         (ct/get-att origin-access-identity :S3CanonicalUserId)}
        :Action "s3:GetObject"
        :Resource
        (ct/join "" ["arn:aws:s3:::" bucket "/*"])}]}}
    opts))

(defn- origin-access-identity [& {:as opts :keys [comment]}]
  (u/resource
    "AWS::CloudFront::CloudFrontOriginAccessIdentity"
    {:CloudFrontOriginAccessIdentityConfig
     {:Comment comment}}
    opts))

(defn- static-site-distribution
  [& {:as opts :keys [certificate-arn default-ttl domain-name logging-bucket logging-prefix origin-access-identity static-site-bucket-domain-name tags]}]
  (u/resource
    "AWS::CloudFront::Distribution"
    {:DistributionConfig
     {:Aliases (when domain-name [domain-name])
      :DefaultCacheBehavior
      {:AllowedMethods ["GET" "HEAD"]
       :Compress true
       :DefaultTTL default-ttl
       :ForwardedValues
       {:Cookies {:Forward "none"}
        :QueryString true}
       :LambdaFunctionAssociations
       [{:EventType "viewer-request"
         :IncludeBody false
         :LambdaFunctionARN (ds/local-ref [:global :resources :StaticSiteOriginRequestVersion :PhysicalResourceId])}]
       :TargetOriginId "StaticSiteBucketOrigin"
       :ViewerProtocolPolicy "redirect-to-https"}
      :DefaultRootObject "index.html"
      :Enabled true
      :HttpVersion "http2"
      :IPV6Enabled true
      :Logging
      (if logging-bucket
        {:Bucket logging-bucket
         :IncludeCookies false
         :Prefix logging-prefix}
        ct/no-value)
      :Origins
      [{:DomainName static-site-bucket-domain-name
        :Id "StaticSiteBucketOrigin"
        :S3OriginConfig
        {:OriginAccessIdentity
         (ct/join "" ["origin-access-identity/cloudfront/" origin-access-identity])}}]
      :ViewerCertificate
      (when certificate-arn
        {:AcmCertificateArn certificate-arn
         :MinimumProtocolVersion "TLSv1.2_2021"
         :SslSupportMethod "sni-only"})}
     :Tags (u/tags tags)}
    opts))

(defn- static-site-record-set-group
  [hosted-zone-id domain-name cloudfront-domain-name & {:as opts}]
  (u/resource
    "AWS::Route53::RecordSetGroup"
    {:HostedZoneId hosted-zone-id
     :RecordSets
     [{:Name domain-name
       :Type "A"
       :AliasTarget
       {:HostedZoneId aws-cloudfront-hosted-zone-id
        :DNSName cloudfront-domain-name
        :EvaluateTargetHealth false}}
      {:Name domain-name
       :Type "AAAA"
       :AliasTarget
       {:HostedZoneId aws-cloudfront-hosted-zone-id
        :DNSName cloudfront-domain-name
        :EvaluateTargetHealth false}}]}
    opts))

(defn- static-site [{:keys [certificate-arn domain-name]}]
  {:inputs
   {::ds/config
    {:domain-name domain-name}
    ::ds/start
    (fn [{{:keys [domain-name]} ::ds/config}]
      (let [client (aws/client {:api :route53})]
        {:hosted-zone-id (r53/fetch-hosted-zone-id client domain-name)}))}
   :global
   {:resources
    (->>
      {:StaticSiteCertificate
       (when-not certificate-arn
         (r-cert/dns-validated
           :domain-name domain-name
           :hosted-zone-id (ds/local-ref [:inputs :hosted-zone-id])))
       :StaticSiteOriginRequestPolicy
       {:Type "AWS::IAM::ManagedPolicy"
        :Properties
        {:PolicyDocument
         {:Version "2012-10-17"
          :Statement
          [{:Action ["logs:PutRetentionPolicy"]
            :Effect "Allow"
            :Resource
            (ct/join ""
              ["arn:aws:logs:*:" ct/account-id ":log-group:/aws/lambda/us-east-1."
               ct/stack-name "-StaticSiteOriginRequestFunction-*"])}]}}}
       :StaticSiteOriginRequestRole
       {:Type "AWS::IAM::Role"
        :Properties
        {:AssumeRolePolicyDocument
         {:Version "2012-10-17"
          :Statement
          {:Effect "Allow"
           :Principal
           {:Service ["edgelambda.amazonaws.com" "lambda.amazonaws.com"]}
           :Action "sts:AssumeRole"}}
         :ManagedPolicyArns
         ["arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
          (ct/ref :StaticSiteOriginRequestPolicy)]}}
       :StaticSiteOriginRequestFunction
       {:Type "AWS::Lambda::Function"
        :Properties
        {:Description
         "Rewrite requests for {{sub}}.example.com/{{path}} to
          s3://{{bucket}}/{{sub}}/{{path}}"
         :Handler "index.handler"
         :MemorySize 128
         :Role (ct/arn :StaticSiteOriginRequestRole)
         :Runtime "nodejs18.x"
         :Code
         {:ZipFile
          (ct/join ""
            ["const SUBDOMAIN_BASE = '" (subs domain-name 2) "';\n\n"
             (slurp (io/resource "salmon/construct/static_site/wildcard-origin-request.js"))])}}}
       :StaticSiteOriginRequestVersion
       {:Type "AWS::Lambda::Version"
        :Properties
        {:Description "v4"
         :FunctionName (ct/ref :StaticSiteOriginRequestFunction)}}}
      (me/remove-vals nil?))}
   :logging
   {:resources
    {:LogsBucket (logging-bucket)
     :LogsBucketPolicy (logging-bucket-policy (ct/ref :LogsBucket))}
    :outputs
    (ct/outputs
      {:LogsBucketDomainName
       [(ct/join "" [ct/stack-name "-LogsBucketDomainName"])
        (ct/get-att :LogsBucket :DomainName)]})}
   :regional
   {:resources
    {:OriginAccessIdentity (origin-access-identity :comment "OAI")
     :Distribution
     (static-site-distribution
       :default-ttl 900
       :domain-name domain-name
       :certificate-arn (or certificate-arn
                          (ds/local-ref [:global :resources :StaticSiteCertificate :PhysicalResourceId]))
       :logging-bucket (ds/local-ref [:logging :outputs :LogsBucketDomainName])
       :logging-prefix "cloudfront/"
       :static-site-bucket-domain-name (ct/get-att :StaticSiteBucket :DomainName)
       :origin-access-identity (ct/ref :OriginAccessIdentity))
     :RecordSetGroup
     (static-site-record-set-group
       (ds/local-ref [:inputs :hosted-zone-id])
       domain-name
       (ct/get-att :Distribution :DomainName))
     :StaticSiteBucket (static-site-bucket)
     :StaticSiteBucketPolicy (static-site-bucket-policy (ct/ref :StaticSiteBucket) :OriginAccessIdentity)}}})

(defn- stack [{:keys [capabilities lint? name outputs resources region tags]}]
  (cf/stack
    :capabilities capabilities
    :lint? lint?
    :name name
    :region region
    :tags tags
    :template
    (->> {:AWSTemplateFormatVersion "2010-09-09"
          :Metadata
          {:SalmonOwner "salmon.construct"}
          :Outputs outputs
          :Resources resources}
      (me/remove-vals nil?))))

(defn- ALPHA-group
  "Experimental static site group. Likely to change."
  [name-prefix & {:as opts :keys [logging-region region]}]
  (let [{:keys [inputs global logging regional]} (static-site opts)]
    {:inputs inputs
     :global (when global
               (-> (assoc opts :name (str name-prefix "Global"))
                 (merge global)
                 (assoc :region :us-east-1
                   :capabilities #{"CAPABILITY_IAM"})
                 stack))
     :logging (when logging
                (-> (assoc opts :name (str name-prefix "Logging"))
                  (merge logging)
                  (assoc :region (or logging-region region))
                  stack))
     region (when regional
              (-> (assoc opts :name (str name-prefix "Regional"))
                (merge regional)
                (assoc :region region)
                stack))}))
