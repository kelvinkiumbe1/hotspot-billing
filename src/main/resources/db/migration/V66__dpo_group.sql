-- DPO Group: nineteen countries on one integration.
--
-- The best coverage-per-integration left on the continent, and the aggregator
-- East and Southern African ISPs already use -- cards and most of the regional
-- wallets behind one hosted page. Botswana, Cote d Ivoire, Egypt, Ethiopia,
-- Ghana, Kenya, Malawi, Mauritius, Morocco, Mozambique, Namibia, Nigeria,
-- Rwanda, Senegal, South Africa, Tanzania, Uganda, Zambia, Zimbabwe.
--
-- Two of those had nothing before this. Mauritius and Namibia were Rail.NONE:
-- neither has a domestic gateway anybody outside can reach, so an aggregator is
-- not second-best there, it is the only way in. The unreachable list goes from
-- eleven to nine.
--
-- The only rail here that speaks XML, which brought two problems nothing else
-- had. Building a document means escaping -- an ampersand in a plan name gets
-- 804 Error in XML rather than a payment. And reading one means a parser that
-- will not fetch what the document names, or an intercepted response could read
-- files off this server. Both are handled and both are tested.
--
-- Verified against the live API: a well-formed createToken reaches 802 "Company
-- is not active" while an empty body gets 804 "Error in XML", so the document
-- shape is accepted; 801 names the company token element; and createToken and
-- verifyToken both reach 802 while an invented request name returns 803 "No
-- request or error in Request type name".

ALTER TABLE payment_gateways DROP CONSTRAINT IF EXISTS payment_gateways_kind_check;
ALTER TABLE payment_gateways ADD CONSTRAINT payment_gateways_kind_check
    CHECK (kind IN ('MPESA_API', 'MPESA_PAYBILL_MANUAL', 'MPESA_TILL_MANUAL', 'BANK_TRANSFER',
                    'PAYSTACK', 'FLUTTERWAVE', 'STRIPE', 'MTN_MOMO', 'CHAPA', 'PAYNOW',
                    'AIRTEL_MONEY', 'ORANGE_MONEY', 'WAVE', 'VODACOM_MPESA', 'PAYMOB',
                    'KONNECT', 'WAAFIPAY', 'CMI', 'MULTICAIXA', 'CHARGILY', 'DPO'));

-- Two columns:
--   secret_key -> CompanyToken, which identifies the merchant
--   short_code -> ServiceType, which is the DPO service to bill against. Not a
--                 secret, and short_code already means "the merchant's own
--                 identifier at the provider" for Orange, Vodacom and Paymob.
--
-- No environment: neither sandbox hostname DPO documents resolves, so which
-- credentials the merchant holds decides whether money moves -- the same as
-- WaafiPay.
--
-- No webhook_secret: DPO signs nothing. Its notification is treated as a hint
-- and verifyToken is what settles a payment.
