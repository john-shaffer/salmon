(ns salmon.resource.certificate
  "CloudFormation resource maps for AWS Certificate Manager (ACM)
   certificates.

   The resource definitions are documented at
   https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/aws-resource-certificatemanager-certificate.html"
  (:require [medley.core :as me]
            [salmon.util :as u]))

(defn dns-validated
  "A certificate that is automatically validated through DNS.
   Requires write access to the hosted zone.

   | key                          | description
   | ---------------------------- |
   | `:domain-name`               | (Required) The fully qualified domain name (FQDN) or wildcard with which you want to secure an ACM certificate. Examples: \"www.example.com\", \"*.example.com\".
   | `:hosted-zone-id`            | (Required) The ID of the domain name's hosted zone in the format \"Z111111QQQQQQQ\".
   | `:subject-alternative-names` | (Optional) Additional seq of FQDNs to be included in the Subject Alternative Name extension of the ACM certificate. Example: \"www.example.net\".
   | `:tags`                      | (Optional) map or seq of tags to be processed with [[salmon.util/tags]]
   | ... other keys ...           | (Optional) Passed to [[salmon.util/resource]]

   The domain name(s) must be managed through Route 53 for automatic
   DNS validation to work.
   See https://docs.aws.amazon.com/acm/latest/userguide/dns-validation.html

   AWS::CertificateManager::Certificate is documented at
   https://docs.aws.amazon.com/AWSCloudFormation/latest/UserGuide/aws-resource-certificatemanager-certificate.html"
  [& {:as opts
      :keys [domain-name hosted-zone-id subject-alternative-names tags]}]
  (u/resource
   "AWS::CertificateManager::Certificate"
   (->> {:DomainName domain-name
         :DomainValidationOptions
         [{:DomainName domain-name
           :HostedZoneId hosted-zone-id}]
         :SubjectAlternativeNames subject-alternative-names
         :Tags (u/tags tags)
         :ValidationMethod "DNS"}
        (me/remove-vals nil?))
   opts))
