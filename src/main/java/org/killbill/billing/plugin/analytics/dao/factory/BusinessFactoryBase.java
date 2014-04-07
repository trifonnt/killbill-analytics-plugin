/*
 * Copyright 2010-2014 Ning, Inc.
 * Copyright 2014 The Billing Project, LLC
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package org.killbill.billing.plugin.analytics.dao.factory;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import org.joda.time.LocalDate;
import org.killbill.billing.ObjectType;
import org.killbill.billing.account.api.Account;
import org.killbill.billing.account.api.AccountApiException;
import org.killbill.billing.account.api.AccountUserApi;
import org.killbill.billing.catalog.api.Catalog;
import org.killbill.billing.catalog.api.CatalogApiException;
import org.killbill.billing.catalog.api.CatalogUserApi;
import org.killbill.billing.catalog.api.Plan;
import org.killbill.billing.catalog.api.PlanPhase;
import org.killbill.billing.entitlement.api.SubscriptionApi;
import org.killbill.billing.entitlement.api.SubscriptionApiException;
import org.killbill.billing.entitlement.api.SubscriptionBundle;
import org.killbill.billing.entitlement.api.SubscriptionEvent;
import org.killbill.billing.invoice.api.Invoice;
import org.killbill.billing.invoice.api.InvoiceItem;
import org.killbill.billing.invoice.api.InvoicePayment;
import org.killbill.billing.invoice.api.InvoicePaymentApi;
import org.killbill.billing.invoice.api.InvoiceUserApi;
import org.killbill.billing.payment.api.Payment;
import org.killbill.billing.payment.api.PaymentApi;
import org.killbill.billing.payment.api.PaymentApiException;
import org.killbill.billing.payment.api.PaymentMethod;
import org.killbill.billing.payment.api.Refund;
import org.killbill.billing.plugin.analytics.AnalyticsRefreshException;
import org.killbill.billing.plugin.analytics.dao.CurrencyConversionDao;
import org.killbill.billing.plugin.analytics.dao.model.BusinessModelDaoBase.ReportGroup;
import org.killbill.billing.plugin.analytics.utils.CurrencyConverter;
import org.killbill.billing.util.api.CustomFieldUserApi;
import org.killbill.billing.util.api.RecordIdApi;
import org.killbill.billing.util.api.TagDefinitionApiException;
import org.killbill.billing.util.api.TagUserApi;
import org.killbill.billing.util.audit.AccountAuditLogs;
import org.killbill.billing.util.audit.AuditLog;
import org.killbill.billing.util.audit.ChangeType;
import org.killbill.billing.util.callcontext.CallContext;
import org.killbill.billing.util.callcontext.TenantContext;
import org.killbill.billing.util.customfield.CustomField;
import org.killbill.billing.util.tag.ControlTagType;
import org.killbill.billing.util.tag.Tag;
import org.killbill.billing.util.tag.TagDefinition;
import org.killbill.clock.Clock;
import org.killbill.killbill.osgi.libs.killbill.OSGIKillbillAPI;
import org.killbill.killbill.osgi.libs.killbill.OSGIKillbillDataSource;
import org.killbill.killbill.osgi.libs.killbill.OSGIKillbillLogService;
import org.osgi.service.log.LogService;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;

/**
 * Wrapper around Kill Bill APIs
 * <p/>
 * Note: the code is merciful in case audit logs cannot be retrieved. This is because the auditing code
 * is fairly recent, and we want this plugin to support early versions of Kill Bill (with non audited data).
 */
public abstract class BusinessFactoryBase {

    private static final String REFERENCE_CURRENCY = System.getProperty("org.killbill.billing.plugin.analytics.referenceCurrency", "USD");

    protected final OSGIKillbillLogService logService;
    protected final OSGIKillbillAPI osgiKillbillAPI;
    protected final Clock clock;

    private final CurrencyConversionDao currencyConversionDao;

    public BusinessFactoryBase(final OSGIKillbillLogService logService,
                               final OSGIKillbillAPI osgiKillbillAPI,
                               final OSGIKillbillDataSource osgiKillbillDataSource,
                               final Clock clock) {
        this.logService = logService;
        this.osgiKillbillAPI = osgiKillbillAPI;
        this.clock = clock;
        this.currencyConversionDao = new CurrencyConversionDao(logService, osgiKillbillDataSource);
    }

    //
    // UTILS
    //

    protected CurrencyConverter getCurrencyConverter() {
        return new CurrencyConverter(clock, currencyConversionDao.getCurrencyConversions(REFERENCE_CURRENCY));
    }

    //
    // TENANT
    //

    private static final long INTERNAL_TENANT_RECORD_ID = 0L;

    protected Long getTenantRecordId(final TenantContext context) throws AnalyticsRefreshException {
        // See convention in InternalCallContextFactory
        if (context.getTenantId() == null) {
            return INTERNAL_TENANT_RECORD_ID;
        } else {
            final RecordIdApi recordIdUserApi = getRecordIdUserApi();
            return recordIdUserApi.getRecordId(context.getTenantId(), ObjectType.TENANT, context);
        }
    }

    //
    // ACCOUNT
    //

    protected Account getAccount(final UUID accountId, final TenantContext context) throws AnalyticsRefreshException {
        final AccountUserApi accountUserApi = getAccountUserApi();

        try {
            return accountUserApi.getAccountById(accountId, context);
        } catch (AccountApiException e) {
            logService.log(LogService.LOG_WARNING, "Error retrieving account for id " + accountId, e);
            throw new AnalyticsRefreshException(e);
        }
    }

    protected AuditLog getAccountCreationAuditLog(final UUID accountId, final AccountAuditLogs accountAuditLogs) throws AnalyticsRefreshException {
        final List<AuditLog> auditLogsForAccount = accountAuditLogs.getAuditLogsForAccount();
        for (final AuditLog auditLog : auditLogsForAccount) {
            if (auditLog.getChangeType().equals(ChangeType.INSERT)) {
                return auditLog;
            }
        }

        logService.log(LogService.LOG_WARNING, "Unable to find Account creation audit log for id " + accountId);
        return null;
    }

    protected Long getAccountRecordId(final UUID accountId, final TenantContext context) throws AnalyticsRefreshException {
        final RecordIdApi recordIdUserApi = getRecordIdUserApi();
        return recordIdUserApi.getRecordId(accountId, ObjectType.ACCOUNT, context);
    }

    protected ReportGroup getReportGroup(final UUID accountId, final TenantContext context) throws AnalyticsRefreshException {
        final TagUserApi tagUserApi = getTagUserApi();
        boolean isTestAccount = false;
        boolean isPartnerAccount = false;

        final List<Tag> tagForAccount = tagUserApi.getTagsForObject(accountId, ObjectType.ACCOUNT, false, context);
        for (final Tag tag : tagForAccount) {
            if (ControlTagType.TEST.getId().equals(tag.getTagDefinitionId())) {
                isTestAccount = true;
            } else if (ControlTagType.PARTNER.getId().equals(tag.getTagDefinitionId())) {
                isPartnerAccount = true;
            }
        }

        // Test group has precedence
        if (isTestAccount) {
            return ReportGroup.test;
        } else if (isPartnerAccount) {
            return ReportGroup.partner;
        } else {
            return null;
        }
    }

    //
    // SUBSCRIPTION
    //

    protected List<SubscriptionBundle> getSubscriptionBundlesForAccount(final UUID accountId, final TenantContext context) throws AnalyticsRefreshException {
        final SubscriptionApi subscriptionApi = getSubscriptionApi();

        try {
            return subscriptionApi.getSubscriptionBundlesForAccountId(accountId, context);
        } catch (SubscriptionApiException e) {
            logService.log(LogService.LOG_WARNING, "Error retrieving bundles for account id " + accountId, e);
            throw new AnalyticsRefreshException(e);
        }
    }

    protected SubscriptionBundle getLatestSubscriptionBundleForExternalKey(final String bundleExternalKey, final TenantContext context) throws AnalyticsRefreshException {
        final SubscriptionApi subscriptionApi = getSubscriptionApi();

        try {
            final List<SubscriptionBundle> bundles = subscriptionApi.getSubscriptionBundlesForExternalKey(bundleExternalKey, context);
            if (bundles.size() == 0) {
                throw new AnalyticsRefreshException("Unable to retrieve latest bundle for bundle external key " + bundleExternalKey);
            }
            return bundles.get(bundles.size() - 1);
        } catch (SubscriptionApiException e) {
            logService.log(LogService.LOG_WARNING, "Error retrieving bundles for bundle external key " + bundleExternalKey, e);
            throw new AnalyticsRefreshException(e);
        }
    }

    protected Long getBundleRecordId(final UUID bundleId, final TenantContext context) throws AnalyticsRefreshException {
        final RecordIdApi recordIdUserApi = getRecordIdUserApi();
        return recordIdUserApi.getRecordId(bundleId, ObjectType.BUNDLE, context);
    }

    protected AuditLog getBundleCreationAuditLog(final UUID bundleId, final AccountAuditLogs accountAuditLogs) throws AnalyticsRefreshException {
        final List<AuditLog> auditLogsForBundle = accountAuditLogs.getAuditLogsForBundle(bundleId);
        for (final AuditLog auditLog : auditLogsForBundle) {
            if (auditLog.getChangeType().equals(ChangeType.INSERT)) {
                return auditLog;
            }
        }

        logService.log(LogService.LOG_WARNING, "Unable to find Bundle creation audit log for id " + bundleId);
        return null;
    }

    protected AuditLog getSubscriptionEventCreationAuditLog(final UUID subscriptionEventId, final ObjectType objectType, final AccountAuditLogs accountAuditLogs) throws AnalyticsRefreshException {
        final List<AuditLog> auditLogsForSubscriptionEvent = accountAuditLogs.getAuditLogs(objectType).getAuditLogs(subscriptionEventId);
        for (final AuditLog auditLog : auditLogsForSubscriptionEvent) {
            if (auditLog.getChangeType().equals(ChangeType.INSERT)) {
                return auditLog;
            }
        }

        logService.log(LogService.LOG_WARNING, "Unable to find Subscription event creation audit log for id " + subscriptionEventId);
        return null;
    }

    protected Long getSubscriptionEventRecordId(final UUID subscriptionEventId, final ObjectType objectType, final TenantContext context) throws AnalyticsRefreshException {
        final RecordIdApi recordIdUserApi = getRecordIdUserApi();
        return recordIdUserApi.getRecordId(subscriptionEventId, objectType, context);
    }

    //
    // OVERDUE
    //

    protected Iterable<SubscriptionEvent> getBlockingHistory(final UUID accountId, final TenantContext context) throws AnalyticsRefreshException {
        final List<SubscriptionBundle> bundles = getSubscriptionBundlesForAccount(accountId, context);

        // Find all subscription events for that account
        final Iterable<SubscriptionEvent> subscriptionEvents = Iterables.<SubscriptionEvent>concat(Iterables.<SubscriptionBundle, List<SubscriptionEvent>>transform(bundles,
                                                                                                                                                                    new Function<SubscriptionBundle, List<SubscriptionEvent>>() {
                                                                                                                                                                        @Override
                                                                                                                                                                        public List<SubscriptionEvent> apply(final SubscriptionBundle bundle) {
                                                                                                                                                                            return bundle.getTimeline().getSubscriptionEvents();
                                                                                                                                                                        }
                                                                                                                                                                    }
                                                                                                                                                                   ));

        // Filter all service state changes
        return Iterables.<SubscriptionEvent>filter(subscriptionEvents,
                                                   new Predicate<SubscriptionEvent>() {
                                                       @Override
                                                       public boolean apply(final SubscriptionEvent event) {
                                                           return event.getSubscriptionEventType() != null &&
                                                                  // We want events coming from the blocking states table...
                                                                  ObjectType.BLOCKING_STATES.equals(event.getSubscriptionEventType().getObjectType()) &&
                                                                  // ...that are for any service but entitlement
                                                                  !BusinessSubscriptionTransitionFactory.ENTITLEMENT_SERVICE_NAME.equals(event.getServiceName());
                                                       }
                                                   }
                                                  );
    }

    //
    // BLOCKING STATES
    //

    protected AuditLog getBlockingStateCreationAuditLog(final UUID blockingStateId, final AccountAuditLogs accountAuditLogs) throws AnalyticsRefreshException {
        final List<AuditLog> auditLogsForBlockingState = accountAuditLogs.getAuditLogsForBlockingState(blockingStateId);
        for (final AuditLog auditLog : auditLogsForBlockingState) {
            if (auditLog.getChangeType().equals(ChangeType.INSERT)) {
                return auditLog;
            }
        }

        logService.log(LogService.LOG_WARNING, "Unable to find Blocking state creation audit log for id " + blockingStateId);
        return null;
    }

    protected Long getBlockingStateRecordId(final UUID blockingStateId, final TenantContext context) throws AnalyticsRefreshException {
        final RecordIdApi recordIdUserApi = getRecordIdUserApi();
        return recordIdUserApi.getRecordId(blockingStateId, ObjectType.BLOCKING_STATES, context);
    }

    //
    // INVOICE
    //

    protected AuditLog getInvoiceCreationAuditLog(final UUID invoiceId, final AccountAuditLogs accountAuditLogs) throws AnalyticsRefreshException {
        final List<AuditLog> auditLogsForInvoice = accountAuditLogs.getAuditLogsForInvoice(invoiceId);
        for (final AuditLog auditLog : auditLogsForInvoice) {
            if (auditLog.getChangeType().equals(ChangeType.INSERT)) {
                return auditLog;
            }
        }

        logService.log(LogService.LOG_WARNING, "Unable to find Invoice creation audit log for id " + invoiceId);
        return null;
    }

    protected Long getInvoiceRecordId(final UUID invoiceId, final TenantContext context) throws AnalyticsRefreshException {
        final RecordIdApi recordIdUserApi = getRecordIdUserApi();
        return recordIdUserApi.getRecordId(invoiceId, ObjectType.INVOICE, context);
    }

    protected AuditLog getInvoiceItemCreationAuditLog(final UUID invoiceItemId, final AccountAuditLogs accountAuditLogs) throws AnalyticsRefreshException {
        final List<AuditLog> auditLogsForInvoiceItem = accountAuditLogs.getAuditLogsForInvoiceItem(invoiceItemId);
        for (final AuditLog auditLog : auditLogsForInvoiceItem) {
            if (auditLog.getChangeType().equals(ChangeType.INSERT)) {
                return auditLog;
            }
        }

        logService.log(LogService.LOG_WARNING, "Unable to find Invoice item creation audit log for id " + invoiceItemId);
        return null;
    }

    protected Long getInvoiceItemRecordId(final UUID invoiceItemId, final TenantContext context) throws AnalyticsRefreshException {
        final RecordIdApi recordIdUserApi = getRecordIdUserApi();
        return recordIdUserApi.getRecordId(invoiceItemId, ObjectType.INVOICE_ITEM, context);
    }

    protected Collection<Invoice> getInvoicesByAccountId(final UUID accountId, final CallContext context) throws AnalyticsRefreshException {
        final InvoiceUserApi invoiceUserApi = getInvoiceUserApi();
        return invoiceUserApi.getInvoicesByAccount(accountId, context);
    }

    protected BigDecimal getAccountBalance(final UUID accountId, final CallContext context) throws AnalyticsRefreshException {
        final InvoiceUserApi invoiceUserApi = getInvoiceUserApi();
        return invoiceUserApi.getAccountBalance(accountId, context);
    }

    protected Plan getPlanFromInvoiceItem(final InvoiceItem invoiceItem, final TenantContext context) throws AnalyticsRefreshException {
        try {
            final Catalog catalog = getCatalog(context);
            return catalog.findPlan(invoiceItem.getPlanName(), invoiceItem.getStartDate().toDateTimeAtStartOfDay());
        } catch (CatalogApiException e) {
            logService.log(LogService.LOG_INFO, "Unable to retrieve plan for invoice item " + invoiceItem.getId(), e);
            return null;
        }
    }

    protected PlanPhase getPlanPhaseFromInvoiceItem(final InvoiceItem invoiceItem, final LocalDate subscriptionStartDate, final TenantContext context) throws AnalyticsRefreshException {
        try {
            final Catalog catalog = getCatalog(context);
            // TODO - Inaccurate timing
            return catalog.findPhase(invoiceItem.getPhaseName(), invoiceItem.getStartDate().toDateTimeAtStartOfDay(), subscriptionStartDate.toDateTimeAtStartOfDay());
        } catch (CatalogApiException e) {
            logService.log(LogService.LOG_INFO, "Unable to retrieve phase for invoice item " + invoiceItem.getId(), e);
            return null;
        }
    }

    //
    // CATALOG
    //

    protected Catalog getCatalog(final TenantContext context) throws AnalyticsRefreshException {
        final CatalogUserApi catalogUserApi = getCatalogUserApi();
        return catalogUserApi.getCatalog(null, context);
    }

    //
    // INVOICE PAYMENT
    //

    protected Collection<InvoicePayment> getAccountInvoicePayments(final UUID accountId, final TenantContext context) throws AnalyticsRefreshException {
        final InvoicePaymentApi invoicePaymentApi = getInvoicePaymentUserApi();
        final Collection<Payment> payments = getPaymentsByAccountId(accountId, context);

        final Collection<InvoicePayment> allInvoicePayments = new LinkedList<InvoicePayment>();
        for (final Payment payment : payments) {
            // Retrieve all invoice payment types (including refunds and chargebacks) for that payment
            allInvoicePayments.addAll(invoicePaymentApi.getInvoicePayments(payment.getId(), context));
        }

        return allInvoicePayments;
    }

    protected AuditLog getInvoicePaymentCreationAuditLog(final UUID invoicePaymentId, final AccountAuditLogs accountAuditLogs) throws AnalyticsRefreshException {
        final List<AuditLog> auditLogsForInvoicePayment = accountAuditLogs.getAuditLogsForInvoicePayment(invoicePaymentId);
        for (final AuditLog auditLog : auditLogsForInvoicePayment) {
            if (auditLog.getChangeType().equals(ChangeType.INSERT)) {
                return auditLog;
            }
        }

        logService.log(LogService.LOG_WARNING, "Unable to find Invoice payment creation audit log for id " + invoicePaymentId);
        return null;
    }

    protected Long getInvoicePaymentRecordId(final UUID invoicePaymentId, final TenantContext context) throws AnalyticsRefreshException {
        final RecordIdApi recordIdUserApi = getRecordIdUserApi();
        return recordIdUserApi.getRecordId(invoicePaymentId, ObjectType.INVOICE_PAYMENT, context);
    }

    //
    // PAYMENT
    //

    protected Collection<Payment> getPaymentsByAccountId(final UUID accountId, final TenantContext context) throws AnalyticsRefreshException {
        final PaymentApi paymentApi = getPaymentUserApi();
        try {
            return paymentApi.getAccountPayments(accountId, context);
        } catch (PaymentApiException e) {
            logService.log(LogService.LOG_WARNING, "Error retrieving payments for account id " + accountId, e);
            throw new AnalyticsRefreshException(e);
        }
    }

    protected Payment getPaymentWithPluginInfo(final UUID paymentId, final TenantContext context) throws AnalyticsRefreshException {
        final PaymentApi paymentApi = getPaymentUserApi();

        try {
            // Try to get the payment information, with plugin information
            return paymentApi.getPayment(paymentId, true, context);
        } catch (PaymentApiException e) {
            logService.log(LogService.LOG_INFO, "Error retrieving payment with plugin info for id " + paymentId, e);
        }

        try {
            // If we come here, it is possible that the plugin couldn't answer about the payment, maybe
            // because it was deleted in the gateway. Try to return the Kill Bill specific info only
            return paymentApi.getPayment(paymentId, false, context);
        } catch (PaymentApiException e) {
            logService.log(LogService.LOG_WARNING, "Error retrieving payment for id " + paymentId, e);
            throw new AnalyticsRefreshException(e);
        }
    }

    protected Refund getRefundWithPluginInfo(final UUID refundId, final TenantContext context) throws AnalyticsRefreshException {
        final PaymentApi paymentApi = getPaymentUserApi();

        try {
            // Try to get the refund information, with plugin information
            return paymentApi.getRefund(refundId, true, context);
        } catch (PaymentApiException e) {
            logService.log(LogService.LOG_WARNING, "Error retrieving refund for id " + refundId, e);
        }

        try {
            // If we come here, it is possible that the plugin couldn't answer about the refund, maybe
            // because it was deleted in the gateway. Try to return the Kill Bill specific info only
            return paymentApi.getRefund(refundId, false, context);
        } catch (PaymentApiException e) {
            logService.log(LogService.LOG_WARNING, "Error retrieving refund for id " + refundId, e);
            throw new AnalyticsRefreshException(e);
        }
    }

    protected PaymentMethod getPaymentMethod(final UUID paymentMethodId, final TenantContext context) throws AnalyticsRefreshException {
        final PaymentApi paymentApi = getPaymentUserApi();

        try {
            // Try to get all payment methods, including deleted ones, with plugin information
            return paymentApi.getPaymentMethodById(paymentMethodId, true, true, context);
        } catch (PaymentApiException e) {
            logService.log(LogService.LOG_INFO, "Error retrieving payment method for id " + paymentMethodId + ": " + e.getMessage());
        }

        try {
            // If we come here, it is possible that the plugin couldn't answer about the payment method, maybe
            // because it was deleted in the gateway. Try to return the Kill Bill specific info only
            return paymentApi.getPaymentMethodById(paymentMethodId, true, false, context);
        } catch (PaymentApiException e) {
            logService.log(LogService.LOG_INFO, "Error retrieving payment method for id " + paymentMethodId, e);
            return null;
        }
    }

    //
    // FIELD
    //

    protected Collection<CustomField> getFieldsForAccount(final UUID accountId, final TenantContext context) throws AnalyticsRefreshException {
        final CustomFieldUserApi tagUserApi = getCustomFieldUserApi();
        return tagUserApi.getCustomFieldsForAccount(accountId, context);
    }

    protected AuditLog getFieldCreationAuditLog(final UUID fieldId, final AccountAuditLogs accountAuditLogs) throws AnalyticsRefreshException {
        final List<AuditLog> auditLogsForTag = accountAuditLogs.getAuditLogsForCustomField(fieldId);
        for (final AuditLog auditLog : auditLogsForTag) {
            if (auditLog.getChangeType().equals(ChangeType.INSERT)) {
                return auditLog;
            }
        }

        logService.log(LogService.LOG_WARNING, "Unable to find Field creation audit log for id " + fieldId);
        return null;
    }

    protected Long getFieldRecordId(final UUID fieldId, final TenantContext context) throws AnalyticsRefreshException {
        final RecordIdApi recordIdUserApi = getRecordIdUserApi();
        return recordIdUserApi.getRecordId(fieldId, ObjectType.CUSTOM_FIELD, context);
    }

    //
    // TAG
    //

    protected Collection<Tag> getTagsForAccount(final UUID accountId, final TenantContext context) throws AnalyticsRefreshException {
        final TagUserApi tagUserApi = getTagUserApi();
        return tagUserApi.getTagsForAccount(accountId, false, context);
    }

    protected TagDefinition getTagDefinition(final UUID tagDefinitionId, final TenantContext context) throws AnalyticsRefreshException {
        final TagUserApi tagUserApi = getTagUserApi();

        try {
            return tagUserApi.getTagDefinition(tagDefinitionId, context);
        } catch (TagDefinitionApiException e) {
            logService.log(LogService.LOG_WARNING, "Error retrieving tag definition for id " + tagDefinitionId, e);
            throw new AnalyticsRefreshException(e);
        }
    }

    protected AuditLog getTagCreationAuditLog(final UUID tagId, final AccountAuditLogs accountAuditLogs) throws AnalyticsRefreshException {
        final List<AuditLog> auditLogsForTag = accountAuditLogs.getAuditLogsForTag(tagId);
        for (final AuditLog auditLog : auditLogsForTag) {
            if (auditLog.getChangeType().equals(ChangeType.INSERT)) {
                return auditLog;
            }
        }

        logService.log(LogService.LOG_WARNING, "Unable to find Tag creation audit log for id " + tagId);
        return null;
    }

    protected Long getTagRecordId(final UUID tagId, final TenantContext context) throws AnalyticsRefreshException {
        final RecordIdApi recordIdUserApi = getRecordIdUserApi();
        return recordIdUserApi.getRecordId(tagId, ObjectType.TAG, context);
    }

    //
    // APIs
    //

    private AccountUserApi getAccountUserApi() throws AnalyticsRefreshException {
        final AccountUserApi accountUserApi = osgiKillbillAPI.getAccountUserApi();
        if (accountUserApi == null) {
            throw new AnalyticsRefreshException("Error retrieving accountUserApi");
        }
        return accountUserApi;
    }

    private SubscriptionApi getSubscriptionApi() throws AnalyticsRefreshException {
        final SubscriptionApi subscriptionApi = osgiKillbillAPI.getSubscriptionApi();
        if (subscriptionApi == null) {
            throw new AnalyticsRefreshException("Error retrieving subscriptionApi");
        }
        return subscriptionApi;
    }

    private InvoiceUserApi getInvoiceUserApi() throws AnalyticsRefreshException {
        final InvoiceUserApi invoiceUserApi = osgiKillbillAPI.getInvoiceUserApi();
        if (invoiceUserApi == null) {
            throw new AnalyticsRefreshException("Error retrieving invoiceUserApi");
        }
        return invoiceUserApi;
    }

    private CatalogUserApi getCatalogUserApi() throws AnalyticsRefreshException {
        final CatalogUserApi catalogUserApi = osgiKillbillAPI.getCatalogUserApi();
        if (catalogUserApi == null) {
            throw new AnalyticsRefreshException("Error retrieving catalogUserApi");
        }
        return catalogUserApi;
    }

    private PaymentApi getPaymentUserApi() throws AnalyticsRefreshException {
        final PaymentApi paymentApi = osgiKillbillAPI.getPaymentApi();
        if (paymentApi == null) {
            throw new AnalyticsRefreshException("Error retrieving paymentApi");
        }
        return paymentApi;
    }

    private InvoicePaymentApi getInvoicePaymentUserApi() throws AnalyticsRefreshException {
        final InvoicePaymentApi invoicePaymentApi = osgiKillbillAPI.getInvoicePaymentApi();
        if (invoicePaymentApi == null) {
            throw new AnalyticsRefreshException("Error retrieving invoicePaymentApi");
        }
        return invoicePaymentApi;
    }

    private CustomFieldUserApi getCustomFieldUserApi() throws AnalyticsRefreshException {
        final CustomFieldUserApi fieldUserApi = osgiKillbillAPI.getCustomFieldUserApi();
        if (fieldUserApi == null) {
            throw new AnalyticsRefreshException("Error retrieving fieldUserApi");
        }
        return fieldUserApi;
    }

    private TagUserApi getTagUserApi() throws AnalyticsRefreshException {
        final TagUserApi tagUserApi = osgiKillbillAPI.getTagUserApi();
        if (tagUserApi == null) {
            throw new AnalyticsRefreshException("Error retrieving tagUserApi");
        }
        return tagUserApi;
    }

    private RecordIdApi getRecordIdUserApi() throws AnalyticsRefreshException {
        final RecordIdApi recordIdApi = osgiKillbillAPI.getRecordIdApi();
        if (recordIdApi == null) {
            throw new AnalyticsRefreshException("Error retrieving recordIdApi");
        }
        return recordIdApi;
    }
}