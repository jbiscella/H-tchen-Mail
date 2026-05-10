# --- SES email identity ----------------------------------------------------
# Lives in a separate region (var.ses_region — eu-south-1 default) per
# CLAUDE.md §1 ADR. Identity verification kicks off on apply; for an email
# identity AWS sends a confirmation link to the inbox that has to be clicked
# manually. For a domain identity the DKIM tokens become available as DNS
# records that you publish out-of-band.
#
# SES *production access* (i.e. leaving the sandbox) is requested from the
# AWS support console and is not Terraform-manageable.

resource "aws_sesv2_email_identity" "sender" {
  provider       = aws.ses
  email_identity = var.ses_sender_email
}
