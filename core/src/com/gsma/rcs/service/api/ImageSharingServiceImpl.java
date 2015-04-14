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

package com.gsma.rcs.service.api;

import com.gsma.rcs.core.content.ContentManager;
import com.gsma.rcs.core.content.MmContent;
import com.gsma.rcs.core.ims.service.SessionIdGenerator;
import com.gsma.rcs.core.ims.service.richcall.RichcallService;
import com.gsma.rcs.core.ims.service.richcall.image.ImageSharingPersistedStorageAccessor;
import com.gsma.rcs.core.ims.service.richcall.image.ImageTransferSession;
import com.gsma.rcs.platform.file.FileDescription;
import com.gsma.rcs.platform.file.FileFactory;
import com.gsma.rcs.provider.LocalContentResolver;
import com.gsma.rcs.provider.contact.ContactManager;
import com.gsma.rcs.provider.settings.RcsSettings;
import com.gsma.rcs.provider.sharing.ImageSharingDeleteTask;
import com.gsma.rcs.provider.sharing.RichCallHistory;
import com.gsma.rcs.service.broadcaster.ImageSharingEventBroadcaster;
import com.gsma.rcs.service.broadcaster.RcsServiceRegistrationEventBroadcaster;
import com.gsma.rcs.utils.logger.Logger;
import com.gsma.services.rcs.ICommonServiceConfiguration;
import com.gsma.services.rcs.IRcsServiceRegistrationListener;
import com.gsma.services.rcs.RcsService;
import com.gsma.services.rcs.RcsService.Build.VERSION_CODES;
import com.gsma.services.rcs.RcsService.Direction;
import com.gsma.services.rcs.RcsServiceRegistration;
import com.gsma.services.rcs.contact.ContactId;
import com.gsma.services.rcs.sharing.image.IImageSharing;
import com.gsma.services.rcs.sharing.image.IImageSharingListener;
import com.gsma.services.rcs.sharing.image.IImageSharingService;
import com.gsma.services.rcs.sharing.image.IImageSharingServiceConfiguration;
import com.gsma.services.rcs.sharing.image.ImageSharing;
import com.gsma.services.rcs.sharing.image.ImageSharing.ReasonCode;
import com.gsma.services.rcs.sharing.image.ImageSharing.State;

import android.net.Uri;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Image sharing service implementation
 * 
 * @author Jean-Marc AUFFRET
 */
public class ImageSharingServiceImpl extends IImageSharingService.Stub {

    private final ImageSharingEventBroadcaster mBroadcaster = new ImageSharingEventBroadcaster();

    private final RcsServiceRegistrationEventBroadcaster mRcsServiceRegistrationEventBroadcaster = new RcsServiceRegistrationEventBroadcaster();

    private final RichcallService mRichcallService;

    private final RichCallHistory mRichCallLog;

    private final RcsSettings mRcsSettings;

    private final ContactManager mContactManager;

    private final LocalContentResolver mLocalContentResolver;

    private final ExecutorService mImOperationExecutor;

    private final Map<String, IImageSharing> mImageSharingCache = new HashMap<String, IImageSharing>();

    private static final Logger sLogger = Logger.getLogger(ImageSharingServiceImpl.class
            .getSimpleName());

    /**
     * Lock used for synchronization
     */
    private Object lock = new Object();

    private final Object mImsLock;

    /**
     * Constructor
     * 
     * @param richcallService RichcallService
     * @param richCallLog RichCallHistory
     * @param rcsSettings RcsSettings
     * @param contactManager ContactManager
     * @param localContentResolver LocalContentResolver
     * @param imOperationExecutor IM ExecutorService
     * @param imsLock ims lock object
     */
    public ImageSharingServiceImpl(RichcallService richcallService, RichCallHistory richCallLog,
            RcsSettings rcsSettings, ContactManager contactManager,
            LocalContentResolver localContentResolver, ExecutorService imOperationExecutor,
            Object imsLock) {
        if (sLogger.isActivated()) {
            sLogger.info("Image sharing service API is loaded");
        }
        mRichcallService = richcallService;
        mRichCallLog = richCallLog;
        mRcsSettings = rcsSettings;
        mContactManager = contactManager;
        mLocalContentResolver = localContentResolver;
        mImOperationExecutor = imOperationExecutor;
        mImsLock = imsLock;
    }

    /**
     * Close API
     */
    public void close() {
        // Clear list of sessions
        mImageSharingCache.clear();

        if (sLogger.isActivated()) {
            sLogger.info("Image sharing service API is closed");
        }
    }

    /**
     * Add an image sharing in the list
     * 
     * @param imageSharing Image sharing
     */
    private void addImageSharing(ImageSharingImpl imageSharing) {
        if (sLogger.isActivated()) {
            sLogger.debug("Add an image sharing in the list (size=" + mImageSharingCache.size()
                    + ")");
        }

        mImageSharingCache.put(imageSharing.getSharingId(), imageSharing);
    }

    /**
     * Remove an image sharing from the list
     * 
     * @param sharingId Sharing ID
     */
    public void removeImageSharing(String sharingId) {
        if (sLogger.isActivated()) {
            sLogger.debug("Remove an image sharing from the list (size="
                    + mImageSharingCache.size() + ")");
        }

        mImageSharingCache.remove(sharingId);
    }

    /**
     * Returns true if the service is registered to the platform, else returns false
     * 
     * @return Returns true if registered else returns false
     */
    public boolean isServiceRegistered() {
        return ServerApiUtils.isImsConnected();
    }

    /**
     * Return the reason code for IMS service registration
     * 
     * @return the reason code for IMS service registration
     */
    public int getServiceRegistrationReasonCode() {
        return ServerApiUtils.getServiceRegistrationReasonCode().toInt();
    }

    /**
     * Registers a listener on service registration events
     * 
     * @param listener Service registration listener
     */
    public void addEventListener(IRcsServiceRegistrationListener listener) {
        if (sLogger.isActivated()) {
            sLogger.info("Add a service listener");
        }
        synchronized (lock) {
            mRcsServiceRegistrationEventBroadcaster.addEventListener(listener);
        }
    }

    /**
     * Unregisters a listener on service registration events
     * 
     * @param listener Service registration listener
     */
    public void removeEventListener(IRcsServiceRegistrationListener listener) {
        if (sLogger.isActivated()) {
            sLogger.info("Remove a service listener");
        }
        synchronized (lock) {
            mRcsServiceRegistrationEventBroadcaster.removeEventListener(listener);
        }
    }

    public void setImageSharingStateAndReasonCode(ContactId contact, String sharingId, State state,
            ReasonCode reasonCode) {
        mRichCallLog.setImageSharingStateAndReasonCode(sharingId, state, reasonCode);
        mBroadcaster.broadcastStateChanged(contact, sharingId, state, reasonCode);
    }

    /**
     * Notifies registration event
     */
    public void notifyRegistration() {
        // Notify listeners
        synchronized (lock) {
            mRcsServiceRegistrationEventBroadcaster.broadcastServiceRegistered();
        }
    }

    /**
     * Notifies unregistration event
     * 
     * @param reasonCode for unregistration
     */
    public void notifyUnRegistration(RcsServiceRegistration.ReasonCode reasonCode) {
        // Notify listeners
        synchronized (lock) {
            mRcsServiceRegistrationEventBroadcaster.broadcastServiceUnRegistered(reasonCode);
        }
    }

    /**
     * Receive a new image sharing invitation
     * 
     * @param session Image sharing session
     */
    public void receiveImageSharingInvitation(ImageTransferSession session) {
        if (sLogger.isActivated()) {
            sLogger.info("Receive image sharing invitation from " + session.getRemoteContact()
                    + " displayName=" + session.getRemoteDisplayName());
        }
        ContactId contact = session.getRemoteContact();

        // Update displayName of remote contact
        mContactManager.setContactDisplayName(contact, session.getRemoteDisplayName());

        String sharingId = session.getSessionID();
        ImageSharingPersistedStorageAccessor storageAccessor = new ImageSharingPersistedStorageAccessor(
                sharingId, mRichCallLog);
        ImageSharingImpl imageSharing = new ImageSharingImpl(sharingId, mRichcallService,
                mBroadcaster, storageAccessor, this);
        addImageSharing(imageSharing);
        session.addListener(imageSharing);
    }

    /**
     * Returns the configuration of image sharing service
     * 
     * @return Configuration
     */
    public IImageSharingServiceConfiguration getConfiguration() {
        return new IImageSharingServiceConfigurationImpl(mRcsSettings);
    }

    /**
     * Shares an image with a contact. The parameter file contains the URI of the image to be
     * shared(for a local or a remote image). An exception if thrown if there is no ongoing CS call.
     * The parameter contact supports the following formats: MSISDN in national or international
     * format, SIP address, SIP-URI or Tel-URI. If the format of the contact is not supported an
     * exception is thrown.
     * 
     * @param contact Contact ID
     * @param file Uri of file to share
     * @return Image sharing
     * @throws ServerApiException
     */
    public IImageSharing shareImage(ContactId contact, Uri file) throws ServerApiException {
        if (sLogger.isActivated()) {
            sLogger.info("Initiate an image sharing session with " + contact);
        }

        // Test IMS connection
        ServerApiUtils.testIms();

        try {
            FileDescription desc = FileFactory.getFactory().getFileDescription(file);
            MmContent content = ContentManager
                    .createMmContent(file, desc.getSize(), desc.getName());
            long timestamp = System.currentTimeMillis();
            final ImageTransferSession session = mRichcallService.initiateImageSharingSession(
                    contact, content, null, timestamp);

            String sharingId = session.getSessionID();
            mRichCallLog.addImageSharing(session.getSessionID(), contact, Direction.OUTGOING,
                    session.getContent(), ImageSharing.State.INITIATING, ReasonCode.UNSPECIFIED,
                    timestamp);
            mBroadcaster.broadcastStateChanged(contact, sharingId, ImageSharing.State.INITIATING,
                    ReasonCode.UNSPECIFIED);

            ImageSharingPersistedStorageAccessor storageAccessor = new ImageSharingPersistedStorageAccessor(
                    sharingId, contact, Direction.OUTGOING, file, content.getName(),
                    content.getEncoding(), content.getSize(), mRichCallLog, timestamp);
            ImageSharingImpl imageSharing = new ImageSharingImpl(sharingId, mRichcallService,
                    mBroadcaster, storageAccessor, this);

            addImageSharing(imageSharing);
            session.addListener(imageSharing);

            new Thread() {
                public void run() {
                    session.startSession();
                }
            }.start();
            return imageSharing;

        } catch (Exception e) {
            // TODO:Handle Security exception in CR026
            if (sLogger.isActivated()) {
                sLogger.error("Unexpected error", e);
            }
            throw new ServerApiException(e.getMessage());
        }
    }

    /**
     * Returns the list of image sharings in progress
     * 
     * @return List of image sharings
     * @throws ServerApiException
     */
    public List<IBinder> getImageSharings() throws ServerApiException {
        if (sLogger.isActivated()) {
            sLogger.info("Get image sharing sessions");
        }

        try {
            List<IBinder> imageSharings = new ArrayList<IBinder>(mImageSharingCache.size());
            for (IImageSharing imageSharing : mImageSharingCache.values()) {
                imageSharings.add(imageSharing.asBinder());
            }
            return imageSharings;

        } catch (Exception e) {
            if (sLogger.isActivated()) {
                sLogger.error("Unexpected error", e);
            }
            throw new ServerApiException(e.getMessage());
        }
    }

    /**
     * Returns a current image sharing from its unique ID
     * 
     * @param sharingId
     * @return Image sharing
     * @throws ServerApiException
     */
    public IImageSharing getImageSharing(String sharingId) throws ServerApiException {
        if (sLogger.isActivated()) {
            sLogger.info("Get image sharing session " + sharingId);
        }
        IImageSharing imageSharing = mImageSharingCache.get(sharingId);
        if (imageSharing != null) {
            return imageSharing;
        }
        ImageSharingPersistedStorageAccessor storageAccessor = new ImageSharingPersistedStorageAccessor(
                sharingId, mRichCallLog);
        return new ImageSharingImpl(sharingId, mRichcallService, mBroadcaster, storageAccessor,
                this);
    }

    /**
     * Add and broadcast image sharing invitation rejection invitation.
     * 
     * @param contact Contact
     * @param content Image content
     * @param reasonCode Reason code
     * @param timestamp Local timestamp when got invitation
     */
    public void addImageSharingInvitationRejected(ContactId contact, MmContent content,
            ReasonCode reasonCode, long timestamp) {
        String sessionId = SessionIdGenerator.getNewId();
        mRichCallLog.addImageSharing(sessionId, contact, Direction.INCOMING, content,
                ImageSharing.State.REJECTED, reasonCode, timestamp);
        mBroadcaster.broadcastInvitation(sessionId);
    }

    /**
     * Adds a listener on image sharing events
     * 
     * @param listener Listener
     */
    public void addEventListener2(IImageSharingListener listener) {
        if (sLogger.isActivated()) {
            sLogger.info("Add an Image sharing event listener");
        }
        synchronized (lock) {
            mBroadcaster.addEventListener(listener);
        }
    }

    /**
     * Removes a listener on image sharing events
     * 
     * @param listener Listener
     */
    public void removeEventListener2(IImageSharingListener listener) {
        if (sLogger.isActivated()) {
            sLogger.info("Remove an Image sharing event listener");
        }
        synchronized (lock) {
            mBroadcaster.removeEventListener(listener);
        }
    }

    /**
     * Deletes all image sharing from history and abort/reject any associated ongoing session if
     * such exists.
     */
    public void deleteImageSharings() {
        mImOperationExecutor.execute(new ImageSharingDeleteTask(this, mRichcallService,
                mLocalContentResolver, mImsLock));
    }

    /**
     * Deletes image sharing with a given contact from history and abort/reject any associated
     * ongoing session if such exists
     * 
     * @param contact
     */
    public void deleteImageSharings2(ContactId contact) {
        mImOperationExecutor.execute(new ImageSharingDeleteTask(this, mRichcallService,
                mLocalContentResolver, mImsLock, contact));
    }

    /**
     * deletes an image sharing by its sharing id from history and abort/reject any associated
     * ongoing session if such exists.
     * 
     * @param sharingId
     */
    public void deleteImageSharing(String sharingId) {
        mImOperationExecutor.execute(new ImageSharingDeleteTask(this, mRichcallService,
                mLocalContentResolver, mImsLock, sharingId));
    }

    /**
     * Returns service version
     * 
     * @return Version
     * @see VERSION_CODES
     * @throws ServerApiException
     */
    public int getServiceVersion() throws ServerApiException {
        return RcsService.Build.API_VERSION;
    }

    /**
     * Returns the common service configuration
     * 
     * @return the common service configuration
     */
    public ICommonServiceConfiguration getCommonConfiguration() {
        return new CommonServiceConfigurationImpl(mRcsSettings);
    }

    public void broadcastDeleted(ContactId contact, Set<String> sharingIds) {
        mBroadcaster.broadcastDeleted(contact, sharingIds);
    }
}
