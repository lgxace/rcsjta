/*******************************************************************************
 * Software Name : RCS IMS Stack
 *
 * Copyright (C) 2010 France Telecom S.A.
 * Copyright (C) 2014 Sony Mobile Communications Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * NOTE: This file has been modified by Sony Mobile Communications Inc.
 * Modifications are licensed under the License.
 ******************************************************************************/

package com.gsma.rcs.core.ims.service.capability;

import com.gsma.rcs.core.CoreException;
import com.gsma.rcs.core.ims.ImsModule;
import com.gsma.rcs.core.ims.network.sip.SipMessageFactory;
import com.gsma.rcs.core.ims.protocol.sip.SipDialogPath;
import com.gsma.rcs.core.ims.protocol.sip.SipException;
import com.gsma.rcs.core.ims.protocol.sip.SipRequest;
import com.gsma.rcs.core.ims.protocol.sip.SipResponse;
import com.gsma.rcs.core.ims.protocol.sip.SipTransactionContext;
import com.gsma.rcs.core.ims.service.ContactInfo;
import com.gsma.rcs.core.ims.service.ContactInfo.RcsStatus;
import com.gsma.rcs.core.ims.service.ContactInfo.RegistrationState;
import com.gsma.rcs.core.ims.service.SessionAuthenticationAgent;
import com.gsma.rcs.provider.contact.ContactManager;
import com.gsma.rcs.provider.settings.RcsSettings;
import com.gsma.rcs.utils.PhoneUtils;
import com.gsma.rcs.utils.logger.Logger;
import com.gsma.services.rcs.contact.ContactId;

/**
 * Options request task
 * 
 * @author Jean-Marc AUFFRET
 */
public class OptionsRequestTask implements Runnable {
    /**
     * IMS module
     */
    private ImsModule mImsModule;

    /**
     * Remote contact
     */
    private ContactId mContact;

    /**
     * Feature tags
     */
    private String[] mFeatureTags;

    /**
     * Dialog path
     */
    private SipDialogPath mDialogPath;

    /**
     * Authentication agent
     */
    private SessionAuthenticationAgent mAuthenticationAgent;

    /**
     * The logger
     */
    private Logger logger = Logger.getLogger(this.getClass().getName());

    private final RcsSettings mRcsSettings;

    private final ContactManager mContactManager;

    /**
     * Constructor
     * 
     * @param parent IMS module
     * @param contact Remote contact identifier
     * @param featureTags Feature tags
     * @param rcsSettings
     * @param contactManager
     */
    public OptionsRequestTask(ImsModule parent, ContactId contact, String[] featureTags,
            RcsSettings rcsSettings, ContactManager contactManager) {
        mImsModule = parent;
        mContact = contact;
        mFeatureTags = featureTags;
        mAuthenticationAgent = new SessionAuthenticationAgent(mImsModule);
        mRcsSettings = rcsSettings;
        mContactManager = contactManager;
    }

    /**
     * Background processing
     */
    public void run() {
        sendOptions();
    }

    /**
     * Send an OPTIONS request
     */
    private void sendOptions() {
        if (logger.isActivated()) {
            logger.info("Send an OPTIONS message to " + mContact);
        }

        try {
            if (!mImsModule.getCurrentNetworkInterface().isRegistered()) {
                if (logger.isActivated()) {
                    logger.debug("IMS not registered, do nothing");
                }
                return;
            }

            // Create a dialog path
            String contactUri = PhoneUtils.formatContactIdToUri(mContact);
            mDialogPath = new SipDialogPath(mImsModule.getSipManager().getSipStack(), mImsModule
                    .getSipManager().getSipStack().generateCallId(), 1, contactUri,
                    ImsModule.IMS_USER_PROFILE.getPublicUri(), contactUri, mImsModule
                            .getSipManager().getSipStack().getServiceRoutePath(), mRcsSettings);

            // Create OPTIONS request
            if (logger.isActivated()) {
                logger.debug("Send first OPTIONS");
            }
            SipRequest options = SipMessageFactory.createOptions(mDialogPath, mFeatureTags);

            // Send OPTIONS request
            sendOptions(options);
        } catch (SipException e) {
            logger.error("OPTIONS request has failed! Contact=".concat(mContact.toString()), e);
            handleError(new CapabilityError(CapabilityError.OPTIONS_FAILED, e));
        } catch (CoreException e) {
            /* TODO: Remove CoreException in the future because it is too generic. */
            logger.error("OPTIONS request has failed! Contact=".concat(mContact.toString()), e);
            handleError(new CapabilityError(CapabilityError.OPTIONS_FAILED, e));
        }
    }

    /**
     * Send OPTIONS message
     * 
     * @param options SIP OPTIONS
     * @throws CoreException
     * @throws SipException
     */
    private void sendOptions(SipRequest options) throws SipException, CoreException {
        if (logger.isActivated()) {
            logger.info("Send OPTIONS");
        }

        // Send OPTIONS request
        SipTransactionContext ctx = mImsModule.getSipManager().sendSipMessageAndWait(options);

        // Analyze the received response
        if (ctx.isSipResponse()) {
            // A response has been received
            if (ctx.getStatusCode() == 200) {
                // 200 OK
                handle200OK(ctx);
            } else if (ctx.getStatusCode() == 407) {
                // 407 Proxy Authentication Required
                handle407Authentication(ctx);
            } else if ((ctx.getStatusCode() == 480) || (ctx.getStatusCode() == 408)) {
                // User not registered
                handleUserNotRegistered(ctx);
            } else if (ctx.getStatusCode() == 404) {
                // User not found
                handleUserNotFound(ctx);
            } else {
                // Other error response
                handleError(new CapabilityError(CapabilityError.OPTIONS_FAILED, ctx.getStatusCode()
                        + " " + ctx.getReasonPhrase()));
            }
        } else {
            if (logger.isActivated()) {
                logger.debug("No response received for OPTIONS");
            }

            // No response received: timeout
            handleError(new CapabilityError(CapabilityError.OPTIONS_FAILED, ctx.getStatusCode()
                    + " " + ctx.getReasonPhrase()));
        }
    }

    /**
     * Handle user not registered
     * 
     * @param ctx SIP transaction context
     */
    private void handleUserNotRegistered(SipTransactionContext ctx) {
        // 408 or 480 response received
        if (logger.isActivated()) {
            logger.info("User " + mContact + " is not registered");
        }
        ContactInfo info = mContactManager.getContactInfo(mContact);
        if (RcsStatus.NO_INFO.equals(info.getRcsStatus())) {
            // If we do not have already some info on this contact
            // We update the database with empty capabilities
            Capabilities capabilities = new Capabilities();
            mContactManager.setContactCapabilities(mContact, capabilities, RcsStatus.NO_INFO,
                    RegistrationState.OFFLINE);
        } else {
            // We have some info on this contact
            // We update the database with its previous infos and set the registration state to
            // offline
            mContactManager.setContactCapabilities(mContact, info.getCapabilities(),
                    info.getRcsStatus(), RegistrationState.OFFLINE);

            // Notify listener
            mImsModule.getCore().getListener()
                    .handleCapabilitiesNotification(mContact, info.getCapabilities());
        }
    }

    /**
     * Handle user not found
     * 
     * @param ctx SIP transaction context
     */
    private void handleUserNotFound(SipTransactionContext ctx) {
        // 404 response received
        if (logger.isActivated()) {
            logger.info("User " + mContact + " is not found");
        }

        // The contact is not RCS
        Capabilities capabilities = new Capabilities();
        mContactManager.setContactCapabilities(mContact, capabilities, RcsStatus.NOT_RCS,
                RegistrationState.UNKNOWN);

        // Notify listener
        mImsModule.getCore().getListener().handleCapabilitiesNotification(mContact, capabilities);
    }

    /**
     * Handle 200 0K response
     * 
     * @param ctx SIP transaction context
     */
    private void handle200OK(SipTransactionContext ctx) {
        // 200 OK response received
        if (logger.isActivated()) {
            logger.info("200 OK response received for " + mContact);
        }

        // Read capabilities
        SipResponse resp = ctx.getSipResponse();
        Capabilities capabilities = CapabilityUtils.extractCapabilities(resp);

        // Update capability time of last response
        mContactManager.updateCapabilitiesTimeLastResponse(mContact);

        // Update the database capabilities
        if (capabilities.isImSessionSupported()) {
            // The contact is RCS capable

            // Note RCS5.1 chapter 2.7.1.1: "a user shall be considered as unregistered when ... a
            // response
            // that included the automata tag defined in [RFC3840]".
            if (capabilities.isSipAutomata()) {
                mContactManager.setContactCapabilities(mContact, capabilities,
                        RcsStatus.RCS_CAPABLE, RegistrationState.OFFLINE);
            } else {
                mContactManager.setContactCapabilities(mContact, capabilities,
                        RcsStatus.RCS_CAPABLE, RegistrationState.ONLINE);
            }
        } else {
            // The contact is not RCS
            mContactManager.setContactCapabilities(mContact, capabilities, RcsStatus.NOT_RCS,
                    RegistrationState.UNKNOWN);
        }

        // Notify listener
        mImsModule.getCore().getListener().handleCapabilitiesNotification(mContact, capabilities);
    }

    /**
     * Handle 407 response
     * 
     * @param ctx SIP transaction context
     * @throws SipException
     * @throws CoreException
     */
    private void handle407Authentication(SipTransactionContext ctx) throws SipException,
            CoreException {
        // 407 response received
        if (logger.isActivated()) {
            logger.info("407 response received");
        }

        SipResponse resp = ctx.getSipResponse();

        // Set the Proxy-Authorization header
        mAuthenticationAgent.readProxyAuthenticateHeader(resp);

        // Increment the Cseq number of the dialog path
        mDialogPath.incrementCseq();

        // Create a second OPTIONS request with the right token
        if (logger.isActivated()) {
            logger.info("Send second OPTIONS");
        }
        SipRequest options = SipMessageFactory.createOptions(mDialogPath, mFeatureTags);

        // Set the Authorization header
        mAuthenticationAgent.setProxyAuthorizationHeader(options);

        // Send OPTIONS request
        sendOptions(options);
    }

    /**
     * Handle error response
     * 
     * @param error Error
     */
    private void handleError(CapabilityError error) {
        // Error
        if (logger.isActivated()) {
            logger.info("Options has failed for contact " + mContact + ": " + error.getErrorCode()
                    + ", reason=" + error.getMessage());
        }

        // We update the database capabilities time of last request
        mContactManager.updateCapabilitiesTimeLastRequest(mContact);
    }
}
