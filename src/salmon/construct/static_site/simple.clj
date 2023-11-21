(ns salmon.construct.static-site.simple
  "A simple static website hosted on S3 and CloudFront.

   This will create a very fast, reliable, and cheap website.
   But it has no logging, and is not the best choice for
   hosting a large number of websites. That is mostly because
   AWS sets limits on the number of resources an account can
   have, and more complex systems can share certain resources."
  (:require [cognitect.aws.client.api :as aws]
            [donut.system :as ds]
            [io.staticweb.cloudformation-templating :as ct]
            [medley.core :as me]
            [salmon.cloudformation :as cf]
            [salmon.resource.certificate :as r-cert]
            [salmon.route53 :as r53]
            [salmon.util :as u]))

(def ^:private aws-cloudfront-hosted-zone-id "Z2FDTNDATAQYW2")

(defn- bucket [& {:as opts :keys [tags]}]
  (u/resource
   "AWS::S3::Bucket"
   {:PublicAccessBlockConfiguration
    {:BlockPublicAcls true}
    :Tags (u/tags tags)
    :WebsiteConfiguration
    {:IndexDocument "index.html"
     :ErrorDocument "error.html"}}
   opts))

(defn- bucket-policy
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

(defn- distribution
  [& {:as opts :keys [bucket-domain-name certificate-arn default-ttl domain-name origin-access-identity tags]}]
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
      :TargetOriginId "StaticSiteBucketOrigin"
      :ViewerProtocolPolicy "redirect-to-https"}
     :DefaultRootObject "index.html"
     :Enabled true
     :HttpVersion "http2"
     :IPV6Enabled true
     :Origins
     [{:DomainName bucket-domain-name
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

(defn- record-set-group
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

(defn- simple-static-site [{:keys [certificate-arn default-ttl domain-name region]}]
  {:inputs
   {::ds/config
    {:domain-names [domain-name]}
    ::ds/start
    (fn [{{:keys [domain-names]} ::ds/config}]
      (let [client (aws/client {:api :route53 :region region})]
        {:hosted-zone-ids
         (->> domain-names
           (map #(do [(keyword %) (r53/fetch-hosted-zone-id client %)]))
           (into {}))}))}
   :global
   {:resources
    (if certificate-arn
      ; This is effectively a no-op, just here to keep the stack state in sync
      ; in the case when the certificate-arn was not present and now is.
      {:OriginAccessIdentity (origin-access-identity :comment "OAI")}
      {:StaticSiteCertificate
       (r-cert/dns-validated
         domain-name
         :hosted-zone-id (ds/local-ref [:inputs :hosted-zone-ids (keyword domain-name)]))})}
   region
   {:resources
    {:OriginAccessIdentity (origin-access-identity :comment "OAI")
     :Bucket (bucket)
     :BucketPolicy (bucket-policy (ct/ref :Bucket) :OriginAccessIdentity)
     :Distribution
     (distribution
      ;; RegionalDomainName is needed to avoid DNS-related issues
      ;; https://stackoverflow.com/questions/38735306/aws-cloudfront-redirecting-to-s3-bucket
       :bucket-domain-name (ct/get-att :Bucket :RegionalDomainName)
       :certificate-arn (or certificate-arn (ds/local-ref [:global :resources :StaticSiteCertificate :PhysicalResourceId]))
       :default-ttl default-ttl
       :domain-name domain-name
       :origin-access-identity (ct/ref :OriginAccessIdentity))
     :RecordSetGroup
     (record-set-group
       (ds/local-ref [:inputs :hosted-zone-ids (keyword domain-name)])
       domain-name
       (ct/get-att :Distribution :DomainName))}}})

(defn- stack [& {:keys [lint? name outputs resources region tags]}]
  (cf/stack
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

(defn ALPHA-group
  "Experimental static site group. Likely to change."
  [name-prefix & {:as opts :keys [region]}]
  (let [{:as m :keys [inputs global]} (simple-static-site opts)
        regional (get m region)]
    {:inputs inputs
     :global (when global
               (-> (assoc opts :name (str name-prefix "Global"))
                   (merge global)
                   (assoc :region :us-east-1)
                   stack))
     region (when regional
              (-> (assoc opts :name (str name-prefix "Regional"))
                  (merge regional)
                  (assoc :region region)
                  stack))}))
