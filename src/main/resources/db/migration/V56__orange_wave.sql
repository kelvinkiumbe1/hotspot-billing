-- Orange Money and Wave: francophone West Africa off the aggregator.
--
-- Senegal, Cote d'Ivoire and the DRC were all reaching customers through
-- Flutterwave, which means an aggregator margin on top of the wallet's own fee
-- for the continent's second-largest wallet network. Orange Money is dominant
-- in Senegal, Mali, Burkina Faso and Guinea and the largest single wallet in
-- Cote d'Ivoire; Wave undercut it on fees and took real share in Senegal and
-- Cote d'Ivoire, which is exactly why an operator there wants both switched on
-- rather than a choice between them.

ALTER TABLE payment_gateways DROP CONSTRAINT IF EXISTS payment_gateways_kind_check;
ALTER TABLE payment_gateways ADD CONSTRAINT payment_gateways_kind_check
    CHECK (kind IN ('MPESA_API', 'MPESA_PAYBILL_MANUAL', 'MPESA_TILL_MANUAL', 'BANK_TRANSFER',
                    'PAYSTACK', 'FLUTTERWAVE', 'STRIPE', 'MTN_MOMO', 'CHAPA', 'PAYNOW',
                    'AIRTEL_MONEY', 'ORANGE_MONEY', 'WAVE'));

-- No new columns. Orange needs three fields rather than the usual two, and the
-- third is a merchant identifier rather than a secret:
--   consumer_key    -> OAuth2 client id
--   consumer_secret -> OAuth2 client secret
--   short_code      -> merchant key (names the account the money lands in)
--
-- short_code already means "the merchant's own identifier at the telco", which
-- is precisely what a merchant key is. Reusing it beats a column that would sit
-- null on every other row.
--
-- Wave fits the card-processor shape exactly:
--   secret_key     -> API key (wave_sn_prod_… / wave_sn_test_…)
--   webhook_secret -> the webhook secret from their dashboard
