package com.spalimited.hotspotbilling.service.i18n;

import com.spalimited.hotspotbilling.service.PortalSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * What the system says to a customer, in their language.
 *
 * <p>Only customer-facing text lives here. The admin stays in English on
 * purpose: it is a working tool for people the operator hires and trains, and
 * machine-translating three thousand admin strings would make a worse product
 * rather than a wider one. A customer standing in a shop with a phone has no
 * such training and no such choice.
 *
 * <p>A missing key falls back to English rather than showing the key itself.
 * A customer who reads "ussd.menu.buy" has been failed twice — once by the
 * translation and once by the fallback.
 *
 * <p>The French, Swahili and Portuguese here should be read by a native
 * speaker before it goes in front of paying customers. It is careful, and it
 * is not the same thing as reviewed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class Messages {

    private final PortalSettingsService portalSettings;

    private static final Map<Language, Map<String, String>> CATALOGUE = new HashMap<>();

    static {
        CATALOGUE.put(Language.EN, Map.ofEntries(
                // --- USSD ---
                Map.entry("ussd.menu", "1. Buy WiFi\n2. My code\n3. My account\n4. Pay by {pay}"),
                Map.entry("ussd.badOption", "Sorry, that isn't one of the options. Please dial again."),
                Map.entry("ussd.error", "Sorry, something went wrong. Please try again shortly."),
                Map.entry("ussd.noPlans", "No packages are on sale right now. Please try again later."),
                Map.entry("ussd.choosePlan", "Choose a package:"),
                Map.entry("ussd.badPlan", "That wasn't one of the packages. Please dial again."),
                Map.entry("ussd.cancelled", "Cancelled. Nothing has been charged."),
                Map.entry("ussd.noNumber", "We couldn't read your number. Please buy from the WiFi page instead."),
                Map.entry("ussd.payFailed", "We couldn't start the {pay} payment. Please try again shortly."),
                Map.entry("ussd.checkPhone", "Check your phone for the {pay} prompt. "
                        + "Your WiFi code arrives by SMS once paid."),
                Map.entry("ussd.checkPhoneRenew", "Check your phone for the {pay} prompt. "
                        + "Your internet stays on once it is paid."),
                Map.entry("ussd.noCode", "No WiFi code found for this number. Dial again and choose 1 to buy one."),
                Map.entry("ussd.yourCode", "Your code is {code} ({plan}, {state}). "
                        + "Use it as both username and password."),
                Map.entry("ussd.noAccount", "No home or office line is registered on this number. "
                        + "Choose 1 to buy a WiFi package."),
                Map.entry("ussd.accountScreen", "{status}, paid to {date}\n1. Renew 1 month ({price})\n0. Exit"),
                Map.entry("ussd.confirm", "{plan} for {price}\n1. Send {pay} request to {phone}\n0. Cancel"),
                Map.entry("ussd.unknownDate", "unknown"),
                Map.entry("status.active", "Active"),
                Map.entry("status.notActive", "Not active"),
                Map.entry("state.ready", "ready to use"),
                Map.entry("state.inUse", "in use"),
                Map.entry("state.finished", "finished"),
                Map.entry("ussd.thanks", "Thank you."),
                Map.entry("ussd.paybill", "Go to M-Pesa > Pay Bill. Business no: {paybill}. "
                        + "Account: your phone number. Your code arrives by SMS."),
                Map.entry("ussd.till", "Go to M-Pesa > Buy Goods. Till no: {till}. Your code arrives by SMS."),
                Map.entry("ussd.noPayDetails", "Please buy from the WiFi page, or call support for payment details."),
                Map.entry("state.active", "active"),
                Map.entry("state.notActive", "not active"),
                Map.entry("state.used", "used"),
                Map.entry("state.expired", "expired"),
                // --- Portal API replies ---
                Map.entry("pay.checkPhone", "Check your phone and enter your {pay} PIN"),
                Map.entry("pay.openingCheckout", "Opening a secure page to complete your payment"),
                Map.entry("pay.noGateway", "No automatic payment method is set up yet."),
                Map.entry("recover.sent", "We've sent your access code by SMS to that number."),
                Map.entry("recover.pending", "Your payment is still being confirmed. "
                        + "Please wait a moment and try again."),
                Map.entry("recover.noSms", "Your payment is confirmed, but we can't text the code from here — "
                        + "please contact support."),
                Map.entry("recover.failed", "That payment didn't go through. If you were charged, contact support."),
                Map.entry("recover.none", "We couldn't find a payment from that number."),
                Map.entry("verify.checking", "We're verifying your payment — "
                        + "you'll get an SMS with your access code shortly."),
                Map.entry("verify.stillActive", "Your pass is still active. We've resent your code by SMS."),
                Map.entry("verify.usedUp", "That pass has been used up — buy another to get back online."),
                Map.entry("verify.unavailable", "Code verification isn't available right now — "
                        + "please contact support.")));

        CATALOGUE.put(Language.FR, Map.ofEntries(
                Map.entry("ussd.menu", "1. Acheter du WiFi\n2. Mon code\n3. Mon compte\n4. Payer par {pay}"),
                Map.entry("ussd.badOption", "Désolé, ce n'est pas une des options. Veuillez composer à nouveau."),
                Map.entry("ussd.error", "Désolé, une erreur est survenue. Veuillez réessayer dans un instant."),
                Map.entry("ussd.noPlans", "Aucun forfait n'est disponible pour le moment. "
                        + "Veuillez réessayer plus tard."),
                Map.entry("ussd.choosePlan", "Choisissez un forfait :"),
                Map.entry("ussd.badPlan", "Ce n'était pas un des forfaits. Veuillez composer à nouveau."),
                Map.entry("ussd.cancelled", "Annulé. Rien n'a été débité."),
                Map.entry("ussd.noNumber", "Nous n'avons pas pu lire votre numéro. "
                        + "Veuillez acheter depuis la page WiFi."),
                Map.entry("ussd.payFailed", "Nous n'avons pas pu lancer le paiement {pay}. "
                        + "Veuillez réessayer dans un instant."),
                Map.entry("ussd.checkPhone", "Vérifiez la demande {pay} sur votre téléphone. "
                        + "Votre code WiFi arrive par SMS une fois payé."),
                Map.entry("ussd.checkPhoneRenew", "Vérifiez la demande {pay} sur votre téléphone. "
                        + "Votre connexion reste active une fois le paiement effectué."),
                Map.entry("ussd.noCode", "Aucun code WiFi trouvé pour ce numéro. "
                        + "Composez à nouveau et choisissez 1 pour en acheter un."),
                Map.entry("ussd.yourCode", "Votre code est {code} ({plan}, {state}). "
                        + "Utilisez-le comme nom d'utilisateur et mot de passe."),
                Map.entry("ussd.noAccount", "Aucune ligne domicile ou bureau n'est enregistrée sur ce numéro. "
                        + "Choisissez 1 pour acheter un forfait WiFi."),
                Map.entry("ussd.accountScreen", "{status}, payé jusqu'au {date}\n1. Renouveler 1 mois ({price})\n0. Quitter"),
                Map.entry("ussd.confirm", "{plan} pour {price}\n1. Envoyer la demande {pay} au {phone}\n0. Annuler"),
                Map.entry("ussd.unknownDate", "inconnue"),
                Map.entry("status.active", "Actif"),
                Map.entry("status.notActive", "Inactif"),
                Map.entry("state.ready", "prêt à l'emploi"),
                Map.entry("state.inUse", "en cours d'utilisation"),
                Map.entry("state.finished", "terminé"),
                Map.entry("ussd.thanks", "Merci."),
                Map.entry("ussd.paybill", "Allez dans M-Pesa > Pay Bill. N° entreprise : {paybill}. "
                        + "Compte : votre numéro de téléphone. Votre code arrive par SMS."),
                Map.entry("ussd.till", "Allez dans M-Pesa > Buy Goods. N° de caisse : {till}. "
                        + "Votre code arrive par SMS."),
                Map.entry("ussd.noPayDetails", "Veuillez acheter depuis la page WiFi, "
                        + "ou appelez le support pour les détails de paiement."),
                Map.entry("state.active", "actif"),
                Map.entry("state.notActive", "inactif"),
                Map.entry("state.used", "utilisé"),
                Map.entry("state.expired", "expiré"),
                Map.entry("pay.checkPhone", "Vérifiez votre téléphone et saisissez votre code {pay}"),
                Map.entry("pay.openingCheckout", "Ouverture d'une page sécurisée pour finaliser votre paiement"),
                Map.entry("pay.noGateway", "Aucun moyen de paiement automatique n'est encore configuré."),
                Map.entry("recover.sent", "Nous avons envoyé votre code d'accès par SMS à ce numéro."),
                Map.entry("recover.pending", "Votre paiement est encore en cours de confirmation. "
                        + "Veuillez patienter un instant et réessayer."),
                Map.entry("recover.noSms", "Votre paiement est confirmé, mais nous ne pouvons pas envoyer "
                        + "le code par SMS d'ici — veuillez contacter le support."),
                Map.entry("recover.failed", "Ce paiement n'a pas abouti. "
                        + "Si vous avez été débité, contactez le support."),
                Map.entry("recover.none", "Nous n'avons trouvé aucun paiement depuis ce numéro."),
                Map.entry("verify.checking", "Nous vérifions votre paiement — "
                        + "vous recevrez votre code d'accès par SMS sous peu."),
                Map.entry("verify.stillActive", "Votre forfait est toujours actif. "
                        + "Nous vous avons renvoyé votre code par SMS."),
                Map.entry("verify.usedUp", "Ce forfait est épuisé — achetez-en un autre pour vous reconnecter."),
                Map.entry("verify.unavailable", "La vérification du code n'est pas disponible pour le moment — "
                        + "veuillez contacter le support.")));

        CATALOGUE.put(Language.SW, Map.ofEntries(
                Map.entry("ussd.menu", "1. Nunua WiFi\n2. Msimbo wangu\n3. Akaunti yangu\n4. Lipa kwa {pay}"),
                Map.entry("ussd.badOption", "Samahani, hiyo si mojawapo ya chaguo. Tafadhali piga tena."),
                Map.entry("ussd.error", "Samahani, kumetokea hitilafu. Tafadhali jaribu tena baadaye kidogo."),
                Map.entry("ussd.noPlans", "Hakuna vifurushi vinavyouzwa kwa sasa. Tafadhali jaribu tena baadaye."),
                Map.entry("ussd.choosePlan", "Chagua kifurushi:"),
                Map.entry("ussd.badPlan", "Hicho hakikuwa mojawapo ya vifurushi. Tafadhali piga tena."),
                Map.entry("ussd.cancelled", "Imeghairiwa. Hujatozwa chochote."),
                Map.entry("ussd.noNumber", "Hatukuweza kusoma nambari yako. "
                        + "Tafadhali nunua kutoka ukurasa wa WiFi."),
                Map.entry("ussd.payFailed", "Hatukuweza kuanzisha malipo ya {pay}. "
                        + "Tafadhali jaribu tena baadaye kidogo."),
                Map.entry("ussd.checkPhone", "Angalia simu yako kwa ujumbe wa {pay}. "
                        + "Msimbo wako wa WiFi utafika kwa SMS ukishalipa."),
                Map.entry("ussd.checkPhoneRenew", "Angalia simu yako kwa ujumbe wa {pay}. "
                        + "Intaneti yako itaendelea ukishalipa."),
                Map.entry("ussd.noCode", "Hakuna msimbo wa WiFi uliopatikana kwa nambari hii. "
                        + "Piga tena na uchague 1 kununua."),
                Map.entry("ussd.yourCode", "Msimbo wako ni {code} ({plan}, {state}). "
                        + "Utumie kama jina la mtumiaji na nenosiri."),
                Map.entry("ussd.noAccount", "Hakuna laini ya nyumbani au ofisini iliyosajiliwa kwa nambari hii. "
                        + "Chagua 1 kununua kifurushi cha WiFi."),
                Map.entry("ussd.accountScreen", "{status}, imelipiwa hadi {date}\n1. Lipia mwezi 1 ({price})\n0. Toka"),
                Map.entry("ussd.confirm", "{plan} kwa {price}\n1. Tuma ombi la {pay} kwa {phone}\n0. Ghairi"),
                Map.entry("ussd.unknownDate", "haijulikani"),
                Map.entry("status.active", "Inatumika"),
                Map.entry("status.notActive", "Haitumiki"),
                Map.entry("state.ready", "tayari kutumika"),
                Map.entry("state.inUse", "inatumika"),
                Map.entry("state.finished", "imekwisha"),
                Map.entry("ussd.thanks", "Asante."),
                Map.entry("ussd.paybill", "Nenda M-Pesa > Pay Bill. Nambari ya biashara: {paybill}. "
                        + "Akaunti: nambari yako ya simu. Msimbo wako utafika kwa SMS."),
                Map.entry("ussd.till", "Nenda M-Pesa > Buy Goods. Nambari ya till: {till}. "
                        + "Msimbo wako utafika kwa SMS."),
                Map.entry("ussd.noPayDetails", "Tafadhali nunua kutoka ukurasa wa WiFi, "
                        + "au piga simu kwa usaidizi kupata maelezo ya malipo."),
                Map.entry("state.active", "inatumika"),
                Map.entry("state.notActive", "haitumiki"),
                Map.entry("state.used", "imetumika"),
                Map.entry("state.expired", "imeisha"),
                Map.entry("pay.checkPhone", "Angalia simu yako na uweke PIN yako ya {pay}"),
                Map.entry("pay.openingCheckout", "Tunafungua ukurasa salama ili kukamilisha malipo yako"),
                Map.entry("pay.noGateway", "Hakuna njia ya malipo ya kiotomatiki iliyowekwa bado."),
                Map.entry("recover.sent", "Tumetuma msimbo wako kwa SMS kwenye nambari hiyo."),
                Map.entry("recover.pending", "Malipo yako bado yanathibitishwa. "
                        + "Tafadhali subiri kidogo kisha ujaribu tena."),
                Map.entry("recover.noSms", "Malipo yako yamethibitishwa, lakini hatuwezi kutuma msimbo "
                        + "kwa SMS kutoka hapa — tafadhali wasiliana na usaidizi."),
                Map.entry("recover.failed", "Malipo hayo hayakufanikiwa. Kama ulitozwa, wasiliana na usaidizi."),
                Map.entry("recover.none", "Hatukupata malipo yoyote kutoka nambari hiyo."),
                Map.entry("verify.checking", "Tunathibitisha malipo yako — "
                        + "utapokea SMS yenye msimbo wako hivi karibuni."),
                Map.entry("verify.stillActive", "Kifurushi chako bado kinatumika. "
                        + "Tumekutumia msimbo wako tena kwa SMS."),
                Map.entry("verify.usedUp", "Kifurushi hicho kimeisha — nunua kingine ili urudi mtandaoni."),
                Map.entry("verify.unavailable", "Uthibitishaji wa msimbo haupatikani kwa sasa — "
                        + "tafadhali wasiliana na usaidizi.")));

        CATALOGUE.put(Language.PT, Map.ofEntries(
                Map.entry("ussd.menu", "1. Comprar WiFi\n2. O meu código\n3. A minha conta\n4. Pagar por {pay}"),
                Map.entry("ussd.badOption", "Desculpe, essa não é uma das opções. Por favor, ligue novamente."),
                Map.entry("ussd.error", "Desculpe, ocorreu um erro. Por favor, tente novamente daqui a pouco."),
                Map.entry("ussd.noPlans", "Não há pacotes à venda neste momento. "
                        + "Por favor, tente novamente mais tarde."),
                Map.entry("ussd.choosePlan", "Escolha um pacote:"),
                Map.entry("ussd.badPlan", "Esse não era um dos pacotes. Por favor, ligue novamente."),
                Map.entry("ussd.cancelled", "Cancelado. Nada foi cobrado."),
                Map.entry("ussd.noNumber", "Não conseguimos ler o seu número. "
                        + "Por favor, compre a partir da página WiFi."),
                Map.entry("ussd.payFailed", "Não conseguimos iniciar o pagamento {pay}. "
                        + "Por favor, tente novamente daqui a pouco."),
                Map.entry("ussd.checkPhone", "Verifique o pedido {pay} no seu telemóvel. "
                        + "O seu código WiFi chega por SMS assim que pagar."),
                Map.entry("ussd.checkPhoneRenew", "Verifique o pedido {pay} no seu telemóvel. "
                        + "A sua internet continua ligada assim que for pago."),
                Map.entry("ussd.noCode", "Nenhum código WiFi encontrado para este número. "
                        + "Ligue novamente e escolha 1 para comprar um."),
                Map.entry("ussd.yourCode", "O seu código é {code} ({plan}, {state}). "
                        + "Use-o como nome de utilizador e palavra-passe."),
                Map.entry("ussd.noAccount", "Nenhuma linha de casa ou escritório está registada neste número. "
                        + "Escolha 1 para comprar um pacote WiFi."),
                Map.entry("ussd.accountScreen", "{status}, pago até {date}\n1. Renovar 1 mês ({price})\n0. Sair"),
                Map.entry("ussd.confirm", "{plan} por {price}\n1. Enviar pedido {pay} para {phone}\n0. Cancelar"),
                Map.entry("ussd.unknownDate", "desconhecida"),
                Map.entry("status.active", "Ativo"),
                Map.entry("status.notActive", "Inativo"),
                Map.entry("state.ready", "pronto a usar"),
                Map.entry("state.inUse", "em uso"),
                Map.entry("state.finished", "terminado"),
                Map.entry("ussd.thanks", "Obrigado."),
                Map.entry("ussd.paybill", "Vá a M-Pesa > Pay Bill. N.º da empresa: {paybill}. "
                        + "Conta: o seu número de telemóvel. O seu código chega por SMS."),
                Map.entry("ussd.till", "Vá a M-Pesa > Buy Goods. N.º de caixa: {till}. "
                        + "O seu código chega por SMS."),
                Map.entry("ussd.noPayDetails", "Por favor, compre a partir da página WiFi, "
                        + "ou ligue para o apoio para obter os dados de pagamento."),
                Map.entry("state.active", "ativo"),
                Map.entry("state.notActive", "inativo"),
                Map.entry("state.used", "usado"),
                Map.entry("state.expired", "expirado"),
                Map.entry("pay.checkPhone", "Verifique o seu telemóvel e introduza o seu PIN {pay}"),
                Map.entry("pay.openingCheckout", "A abrir uma página segura para concluir o seu pagamento"),
                Map.entry("pay.noGateway", "Ainda não há nenhum método de pagamento automático configurado."),
                Map.entry("recover.sent", "Enviámos o seu código de acesso por SMS para esse número."),
                Map.entry("recover.pending", "O seu pagamento ainda está a ser confirmado. "
                        + "Aguarde um momento e tente novamente."),
                Map.entry("recover.noSms", "O seu pagamento está confirmado, mas não conseguimos enviar "
                        + "o código por SMS a partir daqui — por favor, contacte o apoio."),
                Map.entry("recover.failed", "Esse pagamento não foi concluído. "
                        + "Se foi cobrado, contacte o apoio."),
                Map.entry("recover.none", "Não encontrámos nenhum pagamento desse número."),
                Map.entry("verify.checking", "Estamos a verificar o seu pagamento — "
                        + "receberá um SMS com o seu código de acesso em breve."),
                Map.entry("verify.stillActive", "O seu passe ainda está ativo. "
                        + "Reenviámos o seu código por SMS."),
                Map.entry("verify.usedUp", "Esse passe foi todo usado — compre outro para voltar a ficar online."),
                Map.entry("verify.unavailable", "A verificação de códigos não está disponível neste momento — "
                        + "por favor, contacte o apoio.")));
    }

    /** The operator's own setting, used when nothing better is known. */
    public Language operatorLanguage() {
        try {
            return Language.of(portalSettings.settings().getLanguage());
        } catch (Exception e) {
            return Language.fallback();
        }
    }

    /**
     * The language for this customer.
     *
     * <p>Their own preference wins where the operator has allowed it, because
     * the operator's setting is a default rather than a decision about someone
     * else's reading. Where they have not, the operator's choice stands — some
     * deployments genuinely want one language on everything.
     */
    public Language forCustomer(String requested) {
        Language operator = operatorLanguage();
        if (requested == null || requested.isBlank()) {
            return operator;
        }
        try {
            if (!portalSettings.settings().isFollowCustomerLanguage()) {
                return operator;
            }
        } catch (Exception e) {
            return operator;
        }
        return Language.of(requested);
    }

    /** What paying is called for this operator: their own word, or the country's. */
    public String paymentBrand() {
        try {
            var settings = portalSettings.settings();
            if (settings.getPaymentBrand() != null && !settings.getPaymentBrand().isBlank()) {
                return settings.getPaymentBrand().trim();
            }
            return Country.of(settings.getCountry()).paymentBrand();
        } catch (Exception e) {
            return "M-Pesa";
        }
    }

    /** Raw lookup, placeholders untouched — the two-arg form fills {pay} in. */
    String raw(Language language, String key) {
        return lookup(language, key);
    }

    public String get(Language language, String key) {
        return lookup(language, key).replace("{pay}", paymentBrand());
    }

    /**
     * The same, naming the rail the customer actually chose.
     *
     * <p>{@code {pay}} is otherwise filled with the operator's account-wide
     * brand, which was right when one gateway could be live at a time and is
     * wrong now that several can. A customer who picked MTN MoMo was being told
     * to "enter your M-Pesa PIN", because M-Pesa is what the operator calls
     * paying — observed against MTN's sandbox, not imagined.
     *
     * <p>Falls back to the operator's brand when the rail has no label, so a
     * surface that cannot say which one was used still reads sensibly.
     */
    public String forRail(Language language, String key, String railLabel) {
        String brand = railLabel == null || railLabel.isBlank() ? paymentBrand() : railLabel;
        return lookup(language, key).replace("{pay}", brand);
    }

    private String lookup(Language language, String key) {
        Map<String, String> bundle = CATALOGUE.get(language == null ? Language.fallback() : language);
        String value = bundle == null ? null : bundle.get(key);
        if (value != null) {
            return value;
        }
        // English rather than the key. A customer who reads "ussd.menu.buy"
        // has been failed twice — once by the translation and once by the
        // thing meant to cover for it.
        String english = CATALOGUE.get(Language.EN).get(key);
        if (english == null) {
            log.warn("No message for key '{}' in any language", key);
            return "";
        }
        return english;
    }

    /** The same, with {placeholders} filled in. */
    public String get(Language language, String key, Map<String, String> values) {
        String body = get(language, key).replace("{pay}", paymentBrand());
        if (values == null) {
            return body;
        }
        for (Map.Entry<String, String> entry : values.entrySet()) {
            body = body.replace("{" + entry.getKey() + "}",
                    entry.getValue() == null ? "" : entry.getValue());
        }
        return body;
    }

    /** Convenience for the many places that just want the operator's language. */
    public String get(String key) {
        return get(operatorLanguage(), key);
    }

    public String get(String key, Map<String, String> values) {
        return get(operatorLanguage(), key, values);
    }

    /** Every key we hold, for the test that proves no language is missing one. */
    static Map<Language, Map<String, String>> catalogue() {
        return CATALOGUE;
    }
}
