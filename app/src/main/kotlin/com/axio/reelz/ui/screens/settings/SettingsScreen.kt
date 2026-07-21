package com.axio.reelz.ui.screens.settings

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathData
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.compose.ui.platform.LocalUriHandler
import androidx.navigation.NavController
import com.axio.reelz.ui.theme.*
import kotlinx.coroutines.launch

// ── Icon vectors ────────────────────────────────────────────────────────────

private val IconBack: ImageVector get() = ImageVector.Builder("Back", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData { moveTo(15f, 18f); lineTo(9f, 12f); lineTo(15f, 6f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.8f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        fill = SolidColor(Color.Transparent))
}.build()

private val IconStorage: ImageVector get() = ImageVector.Builder("Storage", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(2f, 6f); arcTo(2f, 2f, 0f, false, true, 4f, 4f); lineTo(20f, 4f); arcTo(2f, 2f, 0f, false, true, 22f, 6f)
        arcTo(2f, 2f, 0f, false, true, 20f, 8f); lineTo(4f, 8f); arcTo(2f, 2f, 0f, false, true, 2f, 6f); close()
        moveTo(2f, 12f); arcTo(2f, 2f, 0f, false, true, 4f, 10f); lineTo(20f, 10f); arcTo(2f, 2f, 0f, false, true, 22f, 12f)
        arcTo(2f, 2f, 0f, false, true, 20f, 14f); lineTo(4f, 14f); arcTo(2f, 2f, 0f, false, true, 2f, 12f); close()
        moveTo(2f, 18f); arcTo(2f, 2f, 0f, false, true, 4f, 16f); lineTo(20f, 16f); arcTo(2f, 2f, 0f, false, true, 22f, 18f)
        arcTo(2f, 2f, 0f, false, true, 20f, 20f); lineTo(4f, 20f); arcTo(2f, 2f, 0f, false, true, 2f, 18f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.5f, fill = SolidColor(Color.Transparent))
}.build()

private val IconShield: ImageVector get() = ImageVector.Builder("Shield", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); lineTo(20f, 6f); lineTo(20f, 11f)
        arcTo(9f, 9f, 0f, false, true, 12f, 20f)
        arcTo(9f, 9f, 0f, false, true, 4f, 11f); lineTo(4f, 6f); close()
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.5f, fill = SolidColor(Color.Transparent))
}.build()

private val IconShieldFilled: ImageVector get() = ImageVector.Builder("ShieldFilled", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); lineTo(20f, 6f); lineTo(20f, 11f)
        arcTo(9f, 9f, 0f, false, true, 12f, 20f)
        arcTo(9f, 9f, 0f, false, true, 4f, 11f); lineTo(4f, 6f); close()
    }, stroke = SolidColor(Color.Transparent), fill = SolidColor(Color.White))
}.build()

private val IconBell: ImageVector get() = ImageVector.Builder("Bell", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(18f, 8f); arcTo(6f, 6f, 0f, false, false, 6f, 8f)
        lineTo(5f, 17f); lineTo(19f, 17f); lineTo(18f, 8f)
        moveTo(10.27f, 21f); arcTo(2f, 2f, 0f, false, false, 13.73f, 21f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.5f,
       strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconInfoOutline: ImageVector get() = ImageVector.Builder("Info", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); arcTo(10f, 10f, 0f, false, false, 12f, 22f); arcTo(10f, 10f, 0f, false, false, 12f, 2f); close()
        moveTo(12f, 8f); lineTo(12f, 8.01f); moveTo(12f, 11f); lineTo(12f, 16f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconChevronRight: ImageVector get() = ImageVector.Builder("ChevRight", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData { moveTo(9f, 18f); lineTo(15f, 12f); lineTo(9f, 6f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.6f, strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconChevronDown: ImageVector get() = ImageVector.Builder("ChevDown", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) },
        stroke = SolidColor(Color.White), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconDocument: ImageVector get() = ImageVector.Builder("Document", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(4f, 4f); arcTo(2f, 2f, 0f, false, true, 6f, 2f); lineTo(14f, 2f); lineTo(20f, 8f); lineTo(20f, 20f)
        arcTo(2f, 2f, 0f, false, true, 18f, 22f); lineTo(6f, 22f); arcTo(2f, 2f, 0f, false, true, 4f, 20f); close()
        moveTo(14f, 2f); lineTo(14f, 8f); lineTo(20f, 8f)
        moveTo(9f, 13f); lineTo(15f, 13f); moveTo(9f, 17f); lineTo(13f, 17f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round,
       strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconMail: ImageVector get() = ImageVector.Builder("Mail", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(4f, 4f); lineTo(20f, 4f); arcTo(2f, 2f, 0f, false, true, 22f, 6f); lineTo(22f, 18f)
        arcTo(2f, 2f, 0f, false, true, 20f, 20f); lineTo(4f, 20f); arcTo(2f, 2f, 0f, false, true, 2f, 18f)
        lineTo(2f, 6f); arcTo(2f, 2f, 0f, false, true, 4f, 4f); close()
        moveTo(2f, 6f); lineTo(12f, 13f); lineTo(22f, 6f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round,
       strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconClock: ImageVector get() = ImageVector.Builder("Clock", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(12f, 2f); arcTo(10f, 10f, 0f, false, false, 12f, 22f); arcTo(10f, 10f, 0f, false, false, 12f, 2f); close()
        moveTo(12f, 6f); lineTo(12f, 12f); lineTo(16f, 14f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 1.5f, strokeLineCap = StrokeCap.Round,
       strokeLineJoin = StrokeJoin.Round, fill = SolidColor(Color.Transparent))
}.build()

private val IconListBullet: ImageVector get() = ImageVector.Builder("ListBullet", 24.dp, 24.dp, 24f, 24f).apply {
    addPath(pathData = PathData {
        moveTo(3f, 6f); lineTo(3.01f, 6f); moveTo(3f, 12f); lineTo(3.01f, 12f); moveTo(3f, 18f); lineTo(3.01f, 18f)
        moveTo(8f, 6f); lineTo(21f, 6f); moveTo(8f, 12f); lineTo(21f, 12f); moveTo(8f, 18f); lineTo(21f, 18f)
    }, stroke = SolidColor(Color.White), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, fill = SolidColor(Color.Transparent))
}.build()

private val White30 = Color(0x4DF8F4EE)

// ── Data model for legal-document sections ─────────────────────────────────

private data class LegalSection(val num: String, val title: String, val body: String)

private val privacySections = listOf(
    LegalSection("1", "Introduction and Scope", "1.1 Welcome\n\nAxio Studio (\"Axio Studio,\" \"we,\" \"us,\" or \"our\") respects your privacy and is committed to protecting your personal information. This Privacy Policy (\"Policy\") explains how we collect, use, store, share, and protect information when you access or use Reelz (\"Reelz,\" \"Service,\" or \"Platform\"), including our mobile application for Android and our website (collectively, \"Services\").\n\n1.2 Applicability\n\nThis Policy applies to all users of the Services worldwide. By accessing, downloading, installing, or using the Services, you acknowledge that you have read, understood, and agree to the practices described in this Policy. If you do not agree with this Policy, you must not use the Services.\n\n1.3 Changes to This Policy\n\nWe may update this Policy from time to time to reflect changes in our practices, technology, legal requirements, or Service offerings. We will notify you of material changes by posting the updated Policy on the Services and updating the \"Last Updated\" date. Your continued use of the Services after such changes constitutes your acceptance of the revised Policy. We encourage you to review this Policy periodically.\n\n1.4 Legal Basis for Processing\n\nWe process personal information based on one or more of the following legal grounds: (a) your consent; (b) the necessity to perform a contract with you; (c) compliance with a legal obligation; and (d) our legitimate interests, provided such interests do not override your fundamental rights and freedoms."),
    LegalSection("2", "Information We Collect", "2.1 Information You Provide Directly\n\nWhen you create an Account or use certain features of the Services, you may provide us with:\n\n(a) Account Information: When you sign in with Google Sign-In, we may receive your name, email address, profile picture, and Google account identifier (\"Account Information\").\n\n(b) Communication Information: When you contact our support team, we collect the information you provide, including your email address, the subject line, and the content of your message.\n\n2.2 Information We Collect Automatically\n\nWhen you use the Services, we automatically collect certain information about your use of the Services, including:\n\n(a) Usage Information: We collect information about your interactions with the Services, including but not limited to: watch history, search history, favorites, downloads, \"continue watching\" data, recently played items, clicks, taps, scrolls, time spent using the Services, and feature usage patterns (\"Usage Information\").\n\n(b) Device Information: We collect limited device information necessary to operate the Services, including device type, operating system version, screen resolution, and app version. We do not collect IP addresses.\n\n(c) Log Information: We collect log information related to your use of the Services, including timestamps of access, feature interactions, and error reports.\n\n2.3 Information from Third Parties\n\nWe may receive information about you from third parties, including:\n\n(a) Authentication Providers: When you sign in with Google Sign-In, we receive Account Information as described in Section 2.1(a).\n\n(b) Payment Processors: When you purchase Premium, we receive transaction verification information from the applicable Payment Provider, including transaction ID, status, and timestamp. We do not receive your full payment card information.\n\n(c) Analytics Providers: We receive aggregated and anonymized analytics data from Firebase Analytics and Firebase Crashlytics.\n\n(d) Advertising Partners: We may receive information from Ad Partners regarding ad delivery, impressions, and interactions.\n\n2.4 Information We Do Not Collect\n\nWe do not intentionally collect:\n\n(a) IP addresses;\n(b) Precise geolocation data;\n(c) Sensitive personal information, including racial or ethnic origin, political opinions, religious or philosophical beliefs, trade union membership, genetic data, biometric data, health data, or data concerning sex life or sexual orientation, unless explicitly required by applicable law;\n(d) Information from children under the age of thirteen (13) or the applicable age of digital consent in your jurisdiction."),
    LegalSection("3", "How We Use Your Information", "We use the information we collect for the following purposes:\n\n3.1 To Provide and Maintain the Services\n\nWe use your information to operate, maintain, and provide the features and functionality of the Services, including to authenticate your identity, process Premium transactions, and deliver content recommendations.\n\n3.2 To Personalize Your Experience\n\nWe use Usage Information to personalize your experience, including to provide content recommendations, remember your preferences, and maintain your \"continue watching\" list and favorites.\n\n3.3 To Improve and Develop the Services\n\nWe use aggregated and anonymized information to analyze usage patterns, identify trends, diagnose technical issues, and improve the performance, reliability, and functionality of the Services.\n\n3.4 To Communicate with You\n\nWe use your contact information to respond to your inquiries, provide customer support, send service-related notifications, and inform you of changes to our terms or policies.\n\n3.5 For Security and Fraud Prevention\n\nWe use information to detect, prevent, and respond to fraud, abuse, security risks, and technical issues that could harm our users, the Services, or the public.\n\n3.6 For Legal Compliance\n\nWe use information as necessary to comply with applicable laws, regulations, legal processes, and governmental requests.\n\n3.7 For Advertising (Free Users Only)\n\nFor users who do not subscribe to Premium, we may use non-personally identifiable information to deliver and measure the effectiveness of advertisements served by our Ad Partners."),
    LegalSection("4", "How We Share Your Information", "4.1 Service Providers\n\nWe share information with trusted third-party service providers who perform services on our behalf, including: authentication providers, payment processors, hosting providers, analytics providers, and customer support platforms. These service providers are contractually obligated to use your information only for the purposes for which we disclose it to them and to maintain appropriate security measures.\n\n4.2 Advertising Partners\n\nFor free-tier users, we may share non-personally identifiable information with Ad Partners to deliver targeted advertisements. Ad Partners may collect additional information directly from your device in accordance with their own privacy policies.\n\n4.3 Legal Requirements\n\nWe may disclose your information if required to do so by law or in response to valid requests by public authorities, including to meet national security or law enforcement requirements. We may also disclose your information to enforce our Terms of Service, to protect our rights, privacy, safety, or property, or to respond to an emergency.\n\n4.4 Business Transfers\n\nIn the event of a merger, acquisition, reorganization, sale of assets, or bankruptcy, your information may be transferred as part of the transaction. We will notify you of any such change in ownership or control of your information.\n\n4.5 With Your Consent\n\nWe may share your information with third parties when you have given us your explicit consent to do so.\n\n4.6 We Do Not Sell Your Personal Information\n\nWe do not sell, rent, or trade your personal information to third parties for monetary or other valuable consideration."),
    LegalSection("5", "Cookies and Similar Technologies", "5.1 Use of Cookies and Local Storage\n\nWhen you use the web version of the Services, we and our service providers may use cookies, local storage, session storage, and similar technologies (\"Tracking Technologies\") to: (a) authenticate your session; (b) remember your preferences; (c) analyze usage patterns; and (d) deliver and measure advertising effectiveness.\n\n5.2 Types of Tracking Technologies\n\n(a) Essential Cookies: Necessary for the operation of the Services. These cannot be disabled.\n\n(b) Functional Cookies: Enable enhanced functionality and personalization.\n\n(c) Analytics Cookies: Help us understand how users interact with the Services.\n\n(d) Advertising Cookies: Used by Ad Partners to deliver relevant advertisements.\n\n5.3 Your Choices\n\nYou can manage your cookie preferences through your browser settings. Please note that disabling certain cookies may affect the functionality of the Services."),
    LegalSection("6", "Analytics and Crash Reporting", "6.1 Firebase Analytics\n\nWe use Firebase Analytics, a service provided by Google LLC, to collect aggregated and anonymized information about how users interact with the Services. This helps us understand usage patterns, improve features, and optimize performance. Firebase Analytics may use device identifiers and other technologies to collect information. For more information, please review Google's Privacy Policy.\n\n6.2 Firebase Crashlytics\n\nWe use Firebase Crashlytics, a service provided by Google LLC, to collect crash reports and diagnostic information when the Services experience technical errors. This information helps us identify and fix bugs, improve stability, and enhance the user experience. Crashlytics may collect device state information, stack traces, and other technical data at the time of a crash."),
    LegalSection("7", "Advertising", "7.1 Advertising on Free Tier\n\nUsers who do not subscribe to Premium may be shown advertisements within the Services. Ads may be served by third-party Ad Partners and may be displayed in various formats, including but not limited to: banner ads, interstitial ads, rewarded video ads, and native ads.\n\n7.2 Ad Partner Data Collection\n\nAd Partners may use Tracking Technologies and device identifiers to collect information about your interactions with ads, including impressions, clicks, and conversions. This data collection is subject to the Ad Partners' own privacy policies. We encourage you to review the privacy policies of our Ad Partners.\n\n7.3 Ad Choices\n\nYou may opt out of personalized advertising through your device settings (e.g., Android's \"Opt out of Ads Personalization\" setting) or through industry opt-out mechanisms such as the Network Advertising Initiative (NAI) and the Digital Advertising Alliance (DAA)."),
    LegalSection("8", "Payments", "8.1 Payment Processing\n\nPremium subscriptions are processed by third-party Payment Providers. When you make a payment, you provide your payment information directly to the Payment Provider, not to us. We do not store your full payment card details.\n\n8.2 Information We Receive\n\nWe receive from Payment Providers only the information necessary to verify your transaction, activate your Premium status, and comply with legal obligations, including: transaction ID, transaction status, timestamp, and subscription tier."),
    LegalSection("9", "Data Security", "9.1 Security Measures\n\nWe implement reasonable administrative, technical, and physical safeguards designed to protect your personal information from unauthorized access, use, alteration, disclosure, or destruction. These measures include: HTTPS encryption for data in transit, authenticated API endpoints, access controls, and regular security assessments.\n\n9.2 No Guarantee of Security\n\nDespite our efforts, no method of transmission over the internet or method of electronic storage is completely secure. We cannot guarantee the absolute security of your information. You acknowledge and accept this risk when using the Services.\n\n9.3 Breach Notification\n\nIn the event of a data breach that affects your personal information and is required to be reported under applicable law, we will notify you in accordance with such legal requirements."),
    LegalSection("10", "Data Retention", "10.1 Retention Periods\n\nWe retain your personal information for as long as necessary to fulfill the purposes for which it was collected, including:\n\n(a) Account Information: Retained for the duration of your Account plus any period required by applicable law.\n\n(b) Usage Information: Retained for a period necessary to provide personalized features and improve the Services, after which it is anonymized or deleted.\n\n(c) Transaction Information: Retained for the period required by applicable tax, accounting, and financial regulations.\n\n(d) Communication Records: Retained for the period necessary to resolve disputes and provide customer support.\n\n10.2 Anonymization\n\nAfter the applicable retention period, personal information is either deleted or anonymized so that it can no longer be associated with you. Anonymized data may be retained indefinitely for analytical and research purposes."),
    LegalSection("11", "Your Rights and Choices", "11.1 Access and Correction\n\nYou have the right to request access to the personal information we hold about you and to request correction of any inaccurate or incomplete information. To exercise these rights, contact us at axio.founder@gmail.com with the subject line \"REELZ DATA ACCESS.\"\n\n11.2 Deletion\n\nYou have the right to request deletion of your personal information, subject to our legal obligations and legitimate interests. To request deletion, contact us at axio.founder@gmail.com with the subject line \"REELZ DATA DELETION.\" We will process verified requests within a reasonable timeframe.\n\n11.3 Restriction and Objection\n\nYou have the right to request restriction of processing of your personal information or to object to processing based on legitimate interests. To exercise these rights, contact us at the email address above.\n\n11.4 Data Portability\n\nWhere required by applicable law, you have the right to receive your personal information in a structured, commonly used, and machine-readable format and to transmit that information to another controller.\n\n11.5 Withdrawal of Consent\n\nWhere we rely on your consent to process your personal information, you have the right to withdraw that consent at any time. Withdrawal of consent does not affect the lawfulness of processing based on consent before its withdrawal.\n\n11.6 Response Time\n\nWe will respond to all requests within the timeframe required by applicable law, or within a reasonable period if no specific timeframe is mandated. We may need to verify your identity before fulfilling your request."),
    LegalSection("12", "International Data Transfers", "12.1 Global Operations\n\nWe operate globally, and your personal information may be transferred to, stored, and processed in countries other than your country of residence, including countries that may not have data protection laws equivalent to those in your jurisdiction.\n\n12.2 Safeguards\n\nWhen we transfer personal information across borders, we implement appropriate safeguards in accordance with applicable law, including: (a) standard contractual clauses approved by relevant authorities; (b) adequacy decisions where applicable; and (c) other legally recognized transfer mechanisms."),
    LegalSection("13", "Children's Privacy", "13.1 Age Restriction\n\nThe Services are not intended for children under the age of thirteen (13) or the applicable age of digital consent in your jurisdiction (\"Children\"). We do not knowingly collect personal information from Children. If you are a parent or guardian and believe that your child has provided us with personal information without your consent, please contact us immediately at axio.founder@gmail.com, and we will take steps to delete such information.\n\n13.2 Discovery of Underage Users\n\nIf we become aware that we have collected personal information from a Child without verifiable parental consent, we will delete that information as quickly as possible."),
    LegalSection("14", "California Privacy Rights (CCPA/CPRA)", "14.1 Applicability\n\nIf you are a California resident, the California Consumer Privacy Act (\"CCPA\") and California Privacy Rights Act (\"CPRA\") provide you with specific rights regarding your personal information.\n\n14.2 Categories of Personal Information Collected\n\nIn the preceding twelve (12) months, we have collected the following categories of personal information: identifiers (name, email address); commercial information (transaction history); internet or other electronic network activity information (watch history, search history, interactions); and inferences drawn from the above.\n\n14.3 Categories of Personal Information Disclosed\n\nWe have disclosed the following categories of personal information for business purposes: identifiers; commercial information; and internet or other electronic network activity information.\n\n14.4 Your CCPA/CPRA Rights\n\n(a) Right to Know: You have the right to request that we disclose what personal information we have collected, used, disclosed, and sold about you.\n\n(b) Right to Delete: You have the right to request deletion of your personal information, subject to certain exceptions.\n\n(c) Right to Correct: You have the right to request correction of inaccurate personal information.\n\n(d) Right to Opt Out: You have the right to opt out of the sale or sharing of your personal information. We do not sell personal information.\n\n(e) Right to Non-Discrimination: We will not discriminate against you for exercising your CCPA/CPRA rights.\n\n14.5 Exercising Your Rights\n\nTo exercise your CCPA/CPRA rights, contact us at axio.founder@gmail.com with the subject line \"REELZ CCPA REQUEST.\" We will verify your identity before processing your request."),
    LegalSection("15", "EEA, UK & Switzerland Rights (GDPR)", "15.1 Data Controller\n\nFor users in the European Economic Area (\"EEA\"), United Kingdom (\"UK\"), and Switzerland, Axio Studio is the data controller responsible for your personal information.\n\n15.2 Legal Basis for Processing\n\nWe process your personal information under the following legal bases:\n\n(a) Performance of a Contract: Processing necessary to provide the Services and fulfill our contractual obligations to you.\n\n(b) Legitimate Interests: Processing necessary for our legitimate interests, such as improving the Services, ensuring security, and preventing fraud, provided such interests are not overridden by your rights.\n\n(c) Consent: Processing based on your explicit consent, which you may withdraw at any time.\n\n(d) Legal Obligation: Processing necessary to comply with applicable laws and regulations.\n\n15.3 Your GDPR Rights\n\nIf you are in the EEA, UK, or Switzerland, you have the following rights under the General Data Protection Regulation (\"GDPR\") or equivalent local law:\n\n(a) Right to Access: The right to obtain confirmation of whether we process your personal information and to access such information.\n\n(b) Right to Rectification: The right to have inaccurate personal information corrected.\n\n(c) Right to Erasure (\"Right to be Forgotten\"): The right to request deletion of your personal information under certain circumstances.\n\n(d) Right to Restrict Processing: The right to request restriction of processing under certain circumstances.\n\n(e) Right to Data Portability: The right to receive your personal information in a structured, commonly used format and to transmit it to another controller.\n\n(f) Right to Object: The right to object to processing based on legitimate interests or for direct marketing purposes.\n\n(g) Right to Lodge a Complaint: The right to lodge a complaint with a supervisory authority.\n\n15.4 Data Protection Officer\n\nWe have appointed a Data Protection Officer (\"DPO\") responsible for overseeing our data protection practices. You may contact our DPO at axio.founder@gmail.com with the subject line \"REELZ DPO.\"\n\n15.5 Representative in the EU\n\nIf required under GDPR Article 27, we have designated a representative in the EU. Contact details are available upon request."),
    LegalSection("16", "Nigerian Data Protection (NDPR)", "16.1 Applicability\n\nIf you are located in Nigeria, your personal information is processed in accordance with the Nigeria Data Protection Regulation (\"NDPR\") and the Nigeria Data Protection Act, 2023.\n\n16.2 Your NDPR Rights\n\nYou have the right to: (a) request access to your personal information; (b) request rectification of inaccurate information; (c) request erasure of your personal information; (d) object to processing; (e) request restriction of processing; (f) request data portability; and (g) lodge a complaint with the Nigeria Data Protection Commission."),
    LegalSection("17", "Contact Us", "If you have any questions, concerns, or requests regarding this Privacy Policy or our data practices, please contact us at:\n\nAxio Studio\nEmail: axio.founder@gmail.com\nSubject Line: REELZ PRIVACY\n\nWe will make reasonable efforts to respond to your inquiry within the timeframe required by applicable law."),
)

private val termsSections = listOf(
    LegalSection("1", "Agreement to Terms", "1.1 Welcome to Reelz\n\nWelcome to Reelz (\"Reelz,\" \"we,\" \"us,\" or \"our\"), a global entertainment discovery platform operated by Axio Studio (\"Axio Studio,\" \"Company,\" or \"we\"). These Terms of Service (\"Terms,\" \"Agreement,\" or \"ToS\") constitute a legally binding agreement between you (\"User,\" \"you,\" or \"your\") and Axio Studio regarding your access to and use of the Reelz mobile application, website, and all related services, features, content, and functionality (collectively, the \"Service\" or \"Platform\").\n\n1.2 Acceptance of Terms\n\nBY ACCESSING, DOWNLOADING, INSTALLING, OR USING THE SERVICE IN ANY MANNER, YOU ACKNOWLEDGE THAT YOU HAVE READ, UNDERSTOOD, AND AGREE TO BE BOUND BY THESE TERMS AND OUR PRIVACY POLICY, WHICH IS INCORPORATED HEREIN BY REFERENCE. IF YOU DO NOT AGREE TO ALL OF THESE TERMS, YOU MAY NOT ACCESS OR USE THE SERVICE AND MUST IMMEDIATELY DISCONTINUE ALL USE.\n\n1.3 Changes to These Terms\n\nWe reserve the right, at our sole discretion, to modify, amend, or update these Terms at any time. We will provide notice of material changes by posting the revised Terms on the Service and updating the \"Last Updated\" date at the top of this document. Your continued use of the Service following the posting of revised Terms constitutes your acceptance of such changes. If you do not agree to the revised Terms, you must stop using the Service immediately. Material changes will be effective thirty (30) days after posting, except where required by law to be effective sooner. Non-material changes are effective immediately upon posting.\n\n1.4 Electronic Communications\n\nBy using the Service, you consent to receive electronic communications from us, including via email, push notifications, and in-app messages. You agree that all agreements, notices, disclosures, and other communications we provide to you electronically satisfy any legal requirement that such communications be in writing."),
    LegalSection("2", "Eligibility and Account Registration", "2.1 Age Requirements\n\nYou must be at least eighteen (18) years of age, or the age of legal majority in your jurisdiction, whichever is greater, to use the Service. By using the Service, you represent and warrant that you meet these age requirements and have the legal capacity to enter into a binding contract. If you are under the applicable age, you may not use the Service under any circumstances.\n\n2.2 Account Creation\n\nCertain features of the Service may be accessed without creating an account. However, to access Premium features or certain enhanced functionality, you must create an account by authenticating through Google Sign-In (\"Account\"). You agree to provide accurate, current, and complete information during the registration process and to update such information to keep it accurate, current, and complete.\n\n2.3 Account Security\n\nYou are solely responsible for maintaining the confidentiality of your Account credentials and for all activities that occur under your Account. You agree to notify us immediately of any unauthorized access to or use of your Account. We will not be liable for any loss or damage arising from your failure to comply with these security obligations.\n\n2.4 Account Termination and Deletion\n\nYou may request deletion of your Account at any time by contacting our support team at axio.founder@gmail.com with the subject line \"REELZ ACCOUNT DELETION.\" We will process verified deletion requests in accordance with applicable law and our data retention policies. Account deletion may not be immediate and may require verification of ownership. Upon deletion, your access to Premium features and associated data will be permanently removed, subject to our data retention obligations under Section 13 (Data Retention and Deletion).\n\n2.5 Account Suspension and Termination by Us\n\nWe reserve the right, without prior notice and at our sole discretion, to suspend, restrict, or terminate your Account and access to the Service, in whole or in part, for any reason, including but not limited to: (a) violation of these Terms; (b) suspected fraudulent, abusive, or illegal activity; (c) non-payment of fees; (d) prolonged inactivity; or (e) to protect the security, integrity, or operation of the Service. Upon termination, all licenses and rights granted to you under these Terms will immediately cease."),
    LegalSection("3", "Description of the Service", "3.1 Service Overview\n\nReelz is a global entertainment discovery platform that enables users to discover, browse, search, stream, and download eligible content for offline viewing through publicly available third-party sources (\"Third-Party Content\"). The Service provides metadata, recommendations, search functionality, and organizational tools to enhance content discovery.\n\n3.2 Third-Party Content\n\nThe Service does not host, store, or distribute content directly. All streaming and downloadable content is sourced from licensed third-party providers (\"Content Providers\"). We do not claim ownership of Third-Party Content. The availability, quality, and legality of Third-Party Content are determined solely by the respective Content Providers. We make no representations or warranties regarding the accuracy, completeness, reliability, or legality of any Third-Party Content.\n\n3.3 Service Modifications\n\nWe reserve the right to modify, suspend, discontinue, or restrict access to any aspect of the Service, including features, functionality, content, and availability, at any time and without prior notice. We will not be liable to you or any third party for any modification, suspension, or discontinuation of the Service.\n\n3.4 No Guarantee of Availability\n\nThe Service is provided on an \"AS IS\" and \"AS AVAILABLE\" basis. We do not guarantee that the Service will be uninterrupted, timely, secure, error-free, or free from viruses or other harmful components. Network conditions, third-party service disruptions, and other factors beyond our control may affect Service availability and performance."),
    LegalSection("4", "Premium Subscription", "4.1 Premium Features\n\nReelz offers an optional Premium subscription (\"Premium\") that provides access to additional features, which may include but are not limited to: enhanced download quality, an advertisement-free experience, priority streaming, extended offline storage, and other eligible benefits as described at the time of purchase (\"Premium Features\"). The specific Premium Features available may vary by region, platform, and time.\n\n4.2 Subscription Terms\n\nPremium subscriptions are governed by the terms presented at the time of purchase, which may vary depending on your region and the payment processor used. Subscription terms, including pricing, billing frequency, renewal policies, and cancellation procedures, are determined by the applicable payment processor and are subject to change. You are responsible for reviewing and understanding the specific terms presented by your payment processor before completing any purchase.\n\n4.3 Payment Processing\n\nAll Premium payments are processed by third-party payment processors (\"Payment Providers\"). We do not directly process or store your payment information. By purchasing Premium, you agree to the terms and conditions of the applicable Payment Provider. We receive only the information necessary to verify and process your transaction, activate your Premium status, and comply with legal obligations.\n\n4.4 Refund Policy\n\nAll Premium purchases are final and non-refundable except where required by applicable law or the policies of the applicable Payment Provider. If you believe you are entitled to a refund, you must contact the Payment Provider directly in accordance with their refund policies.\n\n4.5 Grace Period\n\nA one-day grace period may apply following the expiration of your Premium subscription, during which Premium Features may remain accessible. This grace period is discretionary and may be modified or eliminated at any time without notice."),
    LegalSection("5", "Acceptable Use Policy", "5.1 Permitted Use\n\nYou may use the Service only for lawful purposes and in accordance with these Terms. You agree to use the Service in compliance with all applicable local, state, national, and international laws, regulations, and conventions.\n\n5.2 Prohibited Conduct\n\nYou agree not to, and will not permit any third party to:\n\n(a) Reverse engineer, decompile, disassemble, or otherwise attempt to derive the source code, underlying ideas, algorithms, or structure of the Service;\n\n(b) Modify, adapt, translate, or create derivative works based on the Service;\n\n(c) Distribute, license, sell, rent, lease, or otherwise transfer the Service or any portion thereof;\n\n(d) Scrape, crawl, spider, or use any automated means, including bots, scripts, or APIs, to access, monitor, copy, or extract data from the Service without our express written authorization;\n\n(e) Bypass, circumvent, or attempt to bypass or circumvent any security measures, access controls, rate limits, or other protective mechanisms of the Service;\n\n(f) Impersonate any person or entity, or falsely state or otherwise misrepresent your affiliation with any person or entity;\n\n(g) Introduce, upload, transmit, or distribute any malware, viruses, worms, Trojan horses, ransomware, spyware, or other harmful or disruptive code;\n\n(h) Exploit any vulnerabilities, bugs, or errors in the Service for personal gain or to cause harm;\n\n(i) Interfere with or disrupt the integrity, security, or performance of the Service, including by overloading, flooding, or crashing the Service or its servers;\n\n(j) Use the Service to infringe upon the intellectual property rights, privacy rights, or any other rights of any third party;\n\n(k) Use the Service for any illegal, fraudulent, or unauthorized purpose;\n\n(l) Remove, alter, or obscure any copyright, trademark, or other proprietary rights notices contained in the Service;\n\n(m) Frame, mirror, or inline link to any portion of the Service without our express written consent.\n\n5.3 Enforcement\n\nWe reserve the right to investigate and take appropriate legal action against anyone who violates this Acceptable Use Policy, including without limitation, removing offending content, suspending or terminating Accounts, reporting violations to law enforcement authorities, and cooperating fully with any investigation."),
    LegalSection("6", "Intellectual Property Rights", "6.1 Our Intellectual Property\n\nThe Service and its entire contents, features, and functionality, including but not limited to: the Reelz name, logo, trademarks, service marks, trade dress, branding, software, source code, object code, algorithms, databases, user interfaces, graphics, designs, layouts, text, images, audio, video, and all other materials (collectively, \"Our IP\"), are owned by Axio Studio, its licensors, or other providers and are protected by international copyright, trademark, patent, trade secret, and other intellectual property or proprietary rights laws.\n\n6.2 License Grant\n\nSubject to your compliance with these Terms, we grant you a limited, non-exclusive, non-transferable, non-sublicensable, revocable license to access and use the Service for your personal, non-commercial use. This license does not include: (a) any resale or commercial use of the Service; (b) any derivative use of the Service or its contents; (c) any downloading or copying of Account information for the benefit of any third party; or (d) any use of data mining, robots, or similar data gathering and extraction tools.\n\n6.3 Third-Party Content Rights\n\nThird-Party Content displayed or accessible through the Service is the property of the respective Content Providers and is protected by applicable intellectual property laws. Your use of Third-Party Content is subject to the terms and conditions of the respective Content Providers. We do not grant you any rights to Third-Party Content beyond what is necessary to access it through the Service.\n\n6.4 Feedback\n\nIf you provide us with any feedback, suggestions, ideas, or other information regarding the Service (\"Feedback\"), you hereby assign to us all rights, title, and interest in and to such Feedback, and we may use such Feedback for any purpose without compensation or attribution to you."),
    LegalSection("7", "Third-party Services and Content", "7.1 Third-Party Services\n\nThe Service may integrate with, link to, or rely upon third-party services, platforms, and providers for various operational functions, including but not limited to: authentication (e.g., Google Sign-In), payment processing, content delivery, metadata provision, hosting, analytics, advertising, and customer support (collectively, \"Third-Party Services\").\n\n7.2 Third-Party Terms\n\nYour use of Third-Party Services is governed by the respective terms of service, privacy policies, and other agreements of those third parties. We are not responsible for the content, accuracy, policies, practices, or performance of any Third-Party Services. Your interactions with Third-Party Services are solely between you and the applicable third party.\n\n7.3 Third-Party Content Disclaimer\n\nWe do not endorse, verify, or assume any responsibility for Third-Party Content accessible through the Service. All Third-Party Content is provided \"AS IS\" without warranties of any kind. We disclaim all liability for any errors, omissions, inaccuracies, or unlawful content in Third-Party Content."),
    LegalSection("8", "Advertising", "8.1 Advertising on Free Tier\n\nUsers who do not subscribe to Premium may be shown advertisements (\"Ads\") within the Service. Ads may be served by third-party advertising partners (\"Ad Partners\") and may be targeted based on non-personally identifiable information.\n\n8.2 Ad Partner Terms\n\nAd Partners may collect and process information in accordance with their own privacy policies and terms of service. We encourage you to review the privacy policies of our Ad Partners. We are not responsible for the content, accuracy, or practices of any Ad Partners.\n\n8.3 Changes to Advertising\n\nWe reserve the right to change advertising providers, methods, formats, frequency, and targeting criteria at any time without notice. We do not guarantee the absence of Ads for any user, including Premium subscribers, in contexts where Ads are not covered by the Premium subscription (e.g., Third-Party Content that includes its own advertising)."),
    LegalSection("9", "Disclaimers and Limitations of Liability", "9.1 Disclaimer of Warranties\n\nTO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, THE SERVICE IS PROVIDED ON AN \"AS IS,\" \"AS AVAILABLE,\" AND \"WITH ALL FAULTS\" BASIS. WE EXPRESSLY DISCLAIM ALL WARRANTIES OF ANY KIND, WHETHER EXPRESS, IMPLIED, STATUTORY, OR OTHERWISE, INCLUDING BUT NOT LIMITED TO ANY WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE, TITLE, QUIET ENJOYMENT, ACCURACY, NON-INFRINGEMENT, AND ANY WARRANTIES ARISING OUT OF COURSE OF DEALING, USAGE, OR TRADE PRACTICE.\n\nWITHOUT LIMITING THE FOREGOING, WE DO NOT WARRANT THAT: (A) THE SERVICE WILL MEET YOUR REQUIREMENTS; (B) THE SERVICE WILL BE UNINTERRUPTED, TIMELY, SECURE, OR ERROR-FREE; (C) THE RESULTS THAT MAY BE OBTAINED FROM THE USE OF THE SERVICE WILL BE ACCURATE, RELIABLE, OR COMPLETE; (D) THE QUALITY OF ANY PRODUCTS, SERVICES, INFORMATION, OR OTHER MATERIAL OBTAINED THROUGH THE SERVICE WILL MEET YOUR EXPECTATIONS; OR (E) ANY ERRORS IN THE SERVICE WILL BE CORRECTED.\n\n9.2 Limitation of Liability\n\nTO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW, IN NO EVENT SHALL AXIO STUDIO, ITS AFFILIATES, LICENSORS, SERVICE PROVIDERS, EMPLOYEES, AGENTS, OFFICERS, OR DIRECTORS BE LIABLE FOR ANY INDIRECT, INCIDENTAL, SPECIAL, CONSEQUENTIAL, OR PUNITIVE DAMAGES, INCLUDING BUT NOT LIMITED TO: LOSS OF PROFITS, LOSS OF REVENUE, LOSS OF DATA, LOSS OF GOODWILL, BUSINESS INTERRUPTION, COMPUTER FAILURE OR MALFUNCTION, OR ANY OTHER INTANGIBLE LOSSES, ARISING OUT OF OR RELATING TO YOUR ACCESS TO, USE OF, OR INABILITY TO USE THE SERVICE, WHETHER BASED ON WARRANTY, CONTRACT, TORT (INCLUDING NEGLIGENCE), STATUTE, OR ANY OTHER LEGAL THEORY, AND WHETHER OR NOT WE HAVE BEEN INFORMED OF THE POSSIBILITY OF SUCH DAMAGE.\n\n9.3 Cap on Liability\n\nNOTWITHSTANDING ANYTHING TO THE CONTRARY IN THESE TERMS, OUR TOTAL LIABILITY TO YOU FOR ALL CLAIMS ARISING OUT OF OR RELATING TO THESE TERMS OR THE SERVICE, WHETHER IN CONTRACT, TORT, OR OTHERWISE, SHALL NOT EXCEED THE GREATER OF: (A) THE AMOUNT YOU HAVE PAID TO US FOR THE SERVICE IN THE TWELVE (12) MONTHS PRIOR TO THE EVENT GIVING RISE TO LIABILITY; OR (B) ONE HUNDRED UNITED STATES DOLLARS (US$100.00).\n\n9.4 Exclusions\n\nSOME JURISDICTIONS DO NOT ALLOW THE EXCLUSION OR LIMITATION OF CERTAIN WARRANTIES OR LIABILITIES. ACCORDINGLY, SOME OF THE ABOVE LIMITATIONS MAY NOT APPLY TO YOU. IN SUCH CASES, OUR LIABILITY WILL BE LIMITED TO THE MAXIMUM EXTENT PERMITTED BY APPLICABLE LAW."),
    LegalSection("10", "Indemnification", "You agree to defend, indemnify, and hold harmless Axio Studio, its affiliates, licensors, service providers, employees, agents, officers, and directors from and against any and all claims, liabilities, damages, judgments, awards, losses, costs, expenses, or fees (including reasonable attorneys' fees) arising out of or relating to: (a) your violation of these Terms; (b) your use of the Service; (c) your violation of any third-party rights, including intellectual property rights or privacy rights; or (d) your violation of any applicable law, regulation, or convention."),
    LegalSection("11", "Governing Law and Dispute Resolution", "11.1 Governing Law\n\nThese Terms and any dispute arising out of or relating to these Terms or the Service shall be governed by and construed in accordance with the laws of the Federal Republic of Nigeria, without regard to its conflict of law principles. However, if you are a consumer residing in a jurisdiction that mandates the application of local consumer protection laws, such laws shall apply to the extent required and shall not be displaced by this choice of law provision.\n\n11.2 Informal Dispute Resolution\n\nBefore filing any formal legal proceeding, you agree to attempt to resolve any dispute informally by contacting us at axio.founder@gmail.com with the subject line \"REELZ DISPUTE.\" We will make good faith efforts to resolve the dispute within sixty (60) days of receiving your notice.\n\n11.3 Arbitration Agreement\n\nAny dispute, controversy, or claim arising out of or relating to these Terms or the Service, including the formation, interpretation, breach, termination, or validity thereof, shall be finally resolved by binding arbitration administered by the Lagos Court of Arbitration (\"LCA\") in accordance with its Arbitration Rules. The seat of arbitration shall be Lagos, Nigeria. The language of arbitration shall be English. The arbitral tribunal shall consist of one (1) arbitrator. The award of the arbitral tribunal shall be final and binding on the parties.\n\n11.4 Class Action Waiver\n\nYOU AGREE THAT ANY PROCEEDINGS, WHETHER IN ARBITRATION OR COURT, WILL BE CONDUCTED ONLY ON AN INDIVIDUAL BASIS AND NOT IN A CLASS, CONSOLIDATED, OR REPRESENTATIVE ACTION. YOU WAIVE ANY RIGHT TO PARTICIPATE IN A CLASS ACTION AGAINST AXIO STUDIO. If a court or arbitrator determines that this class action waiver is unenforceable, the arbitration agreement in Section 11.3 shall be void as to that dispute.\n\n11.5 Exceptions to Arbitration\n\nNotwithstanding the arbitration agreement in Section 11.3, either party may bring an action in a court of competent jurisdiction: (a) for injunctive or other equitable relief to prevent irreparable harm; (b) for claims that are not subject to arbitration under applicable law; or (c) for claims within the jurisdictional limits of a small claims court."),
    LegalSection("12", "Termination", "12.1 Termination by You\n\nYou may terminate these Terms at any time by discontinuing all use of the Service and deleting your Account in accordance with Section 2.4.\n\n12.2 Termination by Us\n\nWe may suspend or terminate your access to the Service, in whole or in part, at any time and for any reason, with or without notice, including but not limited to: (a) breach of these Terms; (b) suspected fraudulent, abusive, or illegal activity; (c) non-payment of fees; (d) prolonged inactivity; or (e) to protect the security, integrity, or operation of the Service.\n\n12.3 Effect of Termination\n\nUpon termination of these Terms for any reason: (a) all licenses and rights granted to you under these Terms will immediately cease; (b) you must immediately cease all use of the Service; (c) we may delete your Account and associated data in accordance with our data retention policies; and (d) Sections 6 (Intellectual Property Rights), 9 (Disclaimers and Limitations of Liability), 10 (Indemnification), 11 (Governing Law and Dispute Resolution), and 14 (Miscellaneous) shall survive termination."),
    LegalSection("13", "Data Retention and Deletion", "13.1 Data Retention\n\nWe retain personal information for as long as necessary to fulfill the purposes for which it was collected, including to provide the Service, comply with legal obligations, resolve disputes, and enforce our agreements. Specific retention periods are detailed in our Privacy Policy.\n\n13.2 Account Deletion\n\nUpon receipt of a verified Account deletion request, we will delete your personal information from our active systems within a reasonable timeframe, subject to: (a) legal obligations requiring retention; (b) the need to resolve disputes or enforce agreements; and (c) technical limitations preventing complete deletion from backup systems. Anonymized or aggregated data that does not identify you may be retained indefinitely."),
    LegalSection("14", "Miscellaneous", "14.1 Entire Agreement\n\nThese Terms, together with our Privacy Policy and any other policies referenced herein, constitute the entire agreement between you and Axio Studio regarding the Service and supersede all prior or contemporaneous agreements, understandings, negotiations, and discussions, whether oral or written.\n\n14.2 Severability\n\nIf any provision of these Terms is held to be invalid, illegal, or unenforceable by a court of competent jurisdiction, such provision shall be modified to the minimum extent necessary to make it valid and enforceable, or if modification is not possible, such provision shall be severed from these Terms, and the remaining provisions shall continue in full force and effect.\n\n14.3 Waiver\n\nNo waiver of any provision of these Terms shall be effective unless in writing and signed by the party against whom the waiver is sought to be enforced. No failure or delay by either party in exercising any right, power, or privilege under these Terms shall operate as a waiver thereof, nor shall any single or partial exercise of any right, power, or privilege preclude any other or further exercise thereof.\n\n14.4 Assignment\n\nYou may not assign, transfer, or delegate these Terms or any of your rights or obligations hereunder without our prior written consent. We may assign, transfer, or delegate these Terms or any of our rights or obligations hereunder without restriction. These Terms shall be binding upon and inure to the benefit of the parties and their respective successors and permitted assigns.\n\n14.5 Force Majeure\n\nWe shall not be liable for any failure or delay in performing our obligations under these Terms where such failure or delay results from causes beyond our reasonable control, including but not limited to: acts of God, war, terrorism, riots, embargoes, acts of civil or military authorities, fire, floods, accidents, strikes, shortages of transportation, facilities, fuel, energy, labor, or materials, pandemics, epidemics, or failures of telecommunications networks or infrastructure.\n\n14.6 Headings\n\nThe headings and subheadings in these Terms are for convenience only and shall not affect the interpretation of these Terms.\n\n14.7 Contact Information\n\nIf you have any questions, concerns, or comments regarding these Terms, please contact us at:\n\nAxio Studio\nEmail: axio.founder@gmail.com\nSubject Line: REELZ\n\nWe will make reasonable efforts to respond to your inquiry within a reasonable timeframe."),
)// ── Main Settings Screen ─────────────────────────────────────────────────────

@Composable
fun SettingsScreen(nav: NavController) {
    val d = LocalDimensions.current

    var notificationsEnabled by remember { mutableStateOf(true) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
    ) {
        // ── Top bar ────────────────────────────────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.spaceXs),
        ) {
            IconButton(onClick = { nav.popBackStack() }) {
                Icon(IconBack, "Back", tint = White, modifier = Modifier.size(d.iconMd))
            }
            Spacer(Modifier.width(d.spaceXs))
            Column {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        color = White, fontWeight = FontWeight.Black, letterSpacing = (-0.5).sp
                    )
                )
                Text("App preferences", color = Brand, fontSize = d.textSm, fontWeight = FontWeight.SemiBold)
            }
        }

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = d.screenHorizPad)
                .padding(bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(d.spaceSm + d.spaceXxs),
        ) {

            // ── App Section ────────────────────────────────────────────
            SettingsSectionLabel("App")

            SettingsCard(
                icon = IconStorage,
                title = "Storage Usage",
                subtitle = "View your device storage capacity and usage",
                onClick = { nav.navigate("settings_storage") },
            )

            SettingsCard(
                icon = IconDocument,
                iconTint = Brand,
                title = "Terms of Use",
                subtitle = "Read our terms of service",
                onClick = { nav.navigate("settings_terms") },
            )

            SettingsCard(
                icon = IconShield,
                title = "Privacy & Security",
                subtitle = "How your data is handled",
                onClick = { nav.navigate("settings_privacy") },
            )

            SettingsCardWithToggle(
                icon = IconBell,
                title = "Notifications",
                subtitle = "App notifications for updates and reminders",
                checked = notificationsEnabled,
                onCheckedChange = { notificationsEnabled = it },
            )

            Spacer(Modifier.height(d.spaceXs))

            // ── About ──────────────────────────────────────────────────
            SettingsSectionLabel("About")

            SettingsCard(
                icon = IconInfoOutline,
                title = "About Reelz",
                subtitle = "Version info & credits",
                onClick = { nav.navigate("settings_about") },
            )
        }
    }
}

// ── New full-page screens ────────────────────────────────────────────────────

@Composable
fun StorageUsageScreen(nav: NavController) {
    val d = LocalDimensions.current
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
    ) {
        SimpleTopBar(nav, "Storage Usage")

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = d.screenHorizPad)
                .padding(top = d.spaceLg, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(d.spaceMd)
        ) {
            // Hero icon card
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(d.radiusLg))
                    .background(
                        Brush.verticalGradient(
                            listOf(BrandDim.copy(alpha = 0.5f), BgCard)
                        )
                    )
                    .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusLg))
                    .padding(d.spaceLg),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(GlassMd)
                            .border(1.dp, GlassBorderMd, RoundedCornerShape(14.dp)),
                        Alignment.Center,
                    ) { Icon(IconStorage, null, tint = Brand2, modifier = Modifier.size(24.dp)) }
                    Text(
                        "Storage information will appear here soon.",
                        color = White,
                        fontSize = d.textMd,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = (d.textMd.value * 1.5f).sp,
                    )
                    Text(
                        "Total space, used space, available space, and a per-download breakdown will be shown in this section.",
                        color = White60,
                        fontSize = d.textSm,
                        lineHeight = (d.textSm.value * 1.6f).sp,
                    )
                }
            }

            Spacer(Modifier.height(d.spaceXs))

            listOf(
                "Total Device Storage" to "—",
                "Used by Reelz Downloads" to "—",
                "Available Space" to "—",
            ).forEach { (label, value) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(d.radiusMd))
                        .background(BgCard)
                        .border(1.dp, GlassBorder, RoundedCornerShape(d.radiusMd))
                        .padding(horizontal = d.spaceLg, vertical = d.spaceMd),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(label, color = White80, fontSize = d.textSm, fontWeight = FontWeight.Medium)
                    Text(value, color = White40, fontSize = d.textSm, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
// ── Shared Legal Document screen (Terms of Use / Privacy Policy) ───────────
//
// Design goals:
//   • Hero header communicating what the doc is + last-updated date
//   • Sticky "quick jump" chip row so long documents are actually navigable
//   • Each numbered section rendered as its own expandable card, collapsed
//     by default, so users can scan headings instead of facing a wall of text
//   • Sub-headings (e.g. "2.3 Account Security") rendered bold within a
//     section body for visual hierarchy
//   • A contact card at the end for quick access to support
// ─────────────────────────────────────────────────────────────────────────

@Composable
private fun SimpleTopBar(nav: NavController, title: String) {
    val d = LocalDimensions.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { nav.popBackStack() }) {
            Icon(IconBack, "Back", tint = White, modifier = Modifier.size(d.iconMd))
        }
        Spacer(Modifier.width(d.spaceXs))
        Text(title, color = White, fontSize = d.textLg, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun LegalDocumentScreen(
    nav: NavController,
    docTitle: String,
    docIcon: ImageVector,
    accentColor: Color,
    lastUpdated: String,
    introLine: String,
    sections: List<LegalSection>,
    contactSubject: String,
) {
    val d = LocalDimensions.current
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    // Track section expand/collapse state; store Y offsets so quick-jump chips can scroll to them
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    val sectionOffsets = remember { mutableStateMapOf<String, Int>() }

    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
    ) {
        SimpleTopBar(nav, docTitle)

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = d.screenHorizPad)
                .padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(d.spaceMd),
        ) {

            // ── Hero card ──────────────────────────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(d.radiusLg))
                    .background(
                        Brush.linearGradient(
                            listOf(accentColor.copy(alpha = 0.22f), BgCard, BgCard)
                        )
                    )
                    .border(1.dp, accentColor.copy(alpha = 0.28f), RoundedCornerShape(d.radiusLg))
                    .padding(d.spaceLg)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(d.spaceSm)) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(accentColor.copy(alpha = 0.16f))
                            .border(1.dp, accentColor.copy(alpha = 0.35f), RoundedCornerShape(14.dp)),
                        Alignment.Center,
                    ) { Icon(docIcon, null, tint = accentColor, modifier = Modifier.size(24.dp)) }

                    Text(
                        docTitle,
                        color = White,
                        fontSize = d.textXl,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.3).sp,
                    )
                    Text(
                        introLine,
                        color = White70Local(),
                        fontSize = d.textSm,
                        lineHeight = (d.textSm.value * 1.55f).sp,
                    )

                    Row(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(GlassSm)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(IconClock, null, tint = White60, modifier = Modifier.size(13.dp))
                        Text("Last updated $lastUpdated", color = White60, fontSize = d.textXs, fontWeight = FontWeight.Medium)
                    }
                }
            }

            // ── Quick jump ────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(d.spaceXs)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(IconListBullet, null, tint = White40, modifier = Modifier.size(14.dp))
                    Text(
                        "Jump to section",
                        color = White40,
                        fontSize = d.textXs,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.6.sp,
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    sections.forEach { s ->
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(GlassSm)
                                .border(1.dp, GlassBorder, RoundedCornerShape(999.dp))
                                .clickable {
                                    expanded[s.num] = true
                                    coroutineScope.launch {
                                        sectionOffsets[s.num]?.let { y ->
                                            scrollState.animateScrollTo((y - 24).coerceAtLeast(0))
                                        }
                                    }
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(accentColor.copy(alpha = 0.2f)),
                                Alignment.Center,
                            ) {
                                Text(s.num, color = accentColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                            Text(s.title, color = White70Local(), fontSize = d.textXs, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }

            Spacer(Modifier.height(d.spaceXxs))

            // ── Sections ──────────────────────────────────────────────
            sections.forEach { section ->
                val isOpen = expanded[section.num] ?: false
                LegalSectionCard(
                    section = section,
                    accentColor = accentColor,
                    isOpen = isOpen,
                    onToggle = { expanded[section.num] = !isOpen },
                    onPositioned = { y -> sectionOffsets[section.num] = y },
                )
            }

            // ── Contact card ──────────────────────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(d.radiusLg))
                    .background(BgCard)
                    .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusLg))
                    .padding(d.spaceLg)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(d.spaceMd)) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(GlassMd)
                            .border(1.dp, GlassBorderMd, RoundedCornerShape(12.dp)),
                        Alignment.Center,
                    ) { Icon(IconMail, null, tint = Brand2, modifier = Modifier.size(18.dp)) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Questions about this document?", color = White, fontSize = d.textSm, fontWeight = FontWeight.SemiBold)
                        Text("axio.founder@gmail.com", color = White60, fontSize = d.textXs)
                    }
                    IconButton(onClick = {
                        uriHandler.openUri("mailto:axio.founder@gmail.com?subject=$contactSubject")
                    }) {
                        Icon(IconChevronRight, null, tint = White40, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Text(
                "By using Reelz, you acknowledge that you have read and understood this document in full.",
                color = White30,
                fontSize = d.textXs,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = d.spaceXs),
            )
        }
    }
}

// Small helper since White70 isn't defined in tokens but is a nice mid-tone for hero copy
private fun White70Local() = Color(0xB3F4F6FF)

@Composable
private fun LegalSectionCard(
    section: LegalSection,
    accentColor: Color,
    isOpen: Boolean,
    onToggle: () -> Unit,
    onPositioned: (Int) -> Unit,
) {
    val d = LocalDimensions.current
    val rotation by animateFloatAsState(if (isOpen) 180f else 0f, tween(220), label = "chevRot_${section.num}")

    Column(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords -> onPositioned(coords.positionInParent().y.toInt()) }
            .clip(RoundedCornerShape(d.radiusMd))
            .background(BgCard)
            .border(1.dp, if (isOpen) accentColor.copy(alpha = 0.3f) else GlassBorderMd, RoundedCornerShape(d.radiusMd))
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(d.spaceLg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
        ) {
            Box(
                Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(accentColor.copy(alpha = if (isOpen) 0.22f else 0.12f)),
                Alignment.Center,
            ) {
                Text(section.num, color = accentColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text(
                section.title,
                color = White,
                fontSize = d.textSm,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                IconChevronDown, null, tint = White40,
                modifier = Modifier.size(16.dp).rotate(rotation),
            )
        }

        AnimatedVisibility(visible = isOpen, enter = fadeIn(tween(180)) + expandVertically(tween(220)), exit = fadeOut(tween(140)) + shrinkVertically(tween(180))) {
            Column(
                Modifier
                    .padding(horizontal = d.spaceLg)
                    .padding(bottom = d.spaceLg),
                verticalArrangement = Arrangement.spacedBy(d.spaceSm),
            ) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(GlassBorder))
                Spacer(Modifier.height(2.dp))
                LegalBodyText(section.body)
            }
        }
    }
}

// Parses "N.M Sub Title\n\nParagraph..." patterns inside a section body and
// renders sub-headings bold, paragraphs as regular body text.
@Composable
private fun LegalBodyText(body: String) {
    val d = LocalDimensions.current
    val subHeadingRegex = remember { Regex("^\\d{1,2}\\.\\d{1,2}\\s+.+") }
    val blocks = remember(body) { body.split("\n\n").filter { it.isNotBlank() } }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        blocks.forEach { block ->
            val firstLine = block.substringBefore("\n")
            if (subHeadingRegex.matches(firstLine.trim())) {
                val rest = block.substringAfter("\n", "").trim()
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        firstLine.trim(),
                        color = White90,
                        fontSize = d.textSm,
                        fontWeight = FontWeight.Bold,
                        lineHeight = (d.textSm.value * 1.4f).sp,
                    )
                    if (rest.isNotEmpty()) {
                        Text(
                            rest,
                            color = White60,
                            fontSize = d.textSm,
                            lineHeight = (d.textSm.value * 1.6f).sp,
                        )
                    }
                }
            } else {
                Text(
                    block.trim(),
                    color = White60,
                    fontSize = d.textSm,
                    lineHeight = (d.textSm.value * 1.6f).sp,
                )
            }
        }
    }
}
// ── Terms of Use ─────────────────────────────────────────────────────────

@Composable
fun TermsScreen(nav: NavController) {
    LegalDocumentScreen(
        nav = nav,
        docTitle = "Terms of Use",
        docIcon = IconDocument,
        accentColor = Brand,
        lastUpdated = "July 20, 2026",
        introLine = "The rules and conditions that govern your use of Reelz — please read before you stream, download, or subscribe.",
        sections = termsSections,
        contactSubject = "REELZ%20TERMS%20QUESTION",
    )
}

// ── Privacy Policy ───────────────────────────────────────────────────────

@Composable
fun PrivacyPolicyScreen(nav: NavController) {
    LegalDocumentScreen(
        nav = nav,
        docTitle = "Privacy Policy",
        docIcon = IconShield,
        accentColor = Teal,
        lastUpdated = "July 20, 2026",
        introLine = "How Reelz collects, uses, and protects your information — including your rights under GDPR, CCPA, and NDPR.",
        sections = privacySections,
        contactSubject = "REELZ%20PRIVACY%20QUESTION",
    )
}

// ── About ────────────────────────────────────────────────────────────────

@Composable
fun AboutScreen(nav: NavController) {
    val d = LocalDimensions.current
    Column(
        Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
    ) {
        SimpleTopBar(nav, "About Reelz")

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = d.screenHorizPad)
                .padding(top = d.spaceLg, bottom = 40.dp),
            verticalArrangement = Arrangement.spacedBy(d.spaceLg)
        ) {
            // Hero brand card
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(d.radiusLg))
                    .background(
                        Brush.verticalGradient(listOf(BrandDim.copy(alpha = 0.55f), BgCard))
                    )
                    .border(1.dp, BrandBorderLocal(), RoundedCornerShape(d.radiusLg))
                    .padding(d.spaceLg),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Reelz",
                        color = Brand2,
                        fontWeight = FontWeight.Black,
                        fontSize = (d.textXl.value + 6).sp,
                        letterSpacing = (-0.5).sp,
                    )
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(GlassSm)
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text("Version 1.0.0", color = White60, fontSize = d.textXs, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Your personal cinema — stream movies and TV shows, download for offline viewing, and discover what to watch next.",
                        color = White80,
                        fontSize = d.textSm,
                        lineHeight = (d.textSm.value * 1.6f).sp,
                        textAlign = TextAlign.Center,
                    )
                }
            }

            // Credits row
            Column(verticalArrangement = Arrangement.spacedBy(d.spaceXs)) {
                SettingsSectionLabel("Credits")
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(d.radiusMd))
                        .background(BgCard)
                        .border(1.dp, GlassBorder, RoundedCornerShape(d.radiusMd))
                        .padding(d.spaceLg),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(d.spaceMd),
                ) {
                    Text("❤️", fontSize = 20.sp)
                    Column {
                        Text("Built with Kotlin & Jetpack Compose", color = White80, fontSize = d.textSm, fontWeight = FontWeight.Medium)
                        Text("Designed and engineered by Axio Studio", color = White40, fontSize = d.textXs)
                    }
                }
            }

            // Legal quick links
            Column(verticalArrangement = Arrangement.spacedBy(d.spaceXs)) {
                SettingsSectionLabel("Legal")
                SettingsCard(
                    icon = IconDocument,
                    iconTint = Brand,
                    title = "Terms of Use",
                    subtitle = "Read our terms of service",
                    onClick = { nav.navigate("settings_terms") },
                )
                SettingsCard(
                    icon = IconShield,
                    iconTint = Teal,
                    title = "Privacy Policy",
                    subtitle = "How your data is handled",
                    onClick = { nav.navigate("settings_privacy") },
                )
            }

            Text(
                "© 2026 Axio Studio. All rights reserved.",
                color = White30,
                fontSize = d.textXs,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun BrandBorderLocal() = Color(0x330A84FF)

// ── Reusable composables ──────────────────────────────────────────────────

@Composable
private fun SettingsSectionLabel(text: String) {
    val d = LocalDimensions.current
    Text(
        text,
        color = White40,
        fontSize = d.textXs,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingsCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconTint: Color = White60,
    onClick: () -> Unit,
) {
    val d = LocalDimensions.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusMd))
            .background(BgCard)
            .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd))
            .clickable(onClick = onClick)
            .padding(d.spaceLg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceLg - d.spaceXxs),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
                .background(GlassMd)
                .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd - d.spaceXxs)),
            Alignment.Center,
        ) { Icon(icon, null, tint = iconTint, modifier = Modifier.size(d.iconMd - 2.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = White, fontSize = d.textMd, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = White40, fontSize = d.textXs)
        }
        Icon(IconChevronRight, null, tint = White30, modifier = Modifier.size(d.iconMd - 4.dp))
    }
}

@Composable
private fun SettingsCardWithToggle(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconTint: Color = White60,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val d = LocalDimensions.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(d.radiusMd))
            .background(BgCard)
            .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd))
            .padding(d.spaceLg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(d.spaceLg - d.spaceXxs),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(d.radiusMd - d.spaceXxs))
                .background(GlassMd)
                .border(1.dp, GlassBorderMd, RoundedCornerShape(d.radiusMd - d.spaceXxs)),
            Alignment.Center,
        ) { Icon(icon, null, tint = iconTint, modifier = Modifier.size(d.iconMd - 2.dp)) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = White, fontSize = d.textMd, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = White40, fontSize = d.textXs)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Brand,
                uncheckedThumbColor = White30,
                checkedTrackColor = Brand.copy(alpha = 0.3f),
                uncheckedTrackColor = White10,
            )
        )
    }
}
