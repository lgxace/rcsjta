/*
 * Copyright (C) 2014 Sony Mobile Communications Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.gsma.rcs.service.api;

import com.gsma.rcs.core.Core;
import com.gsma.rcs.core.content.ContentManager;
import com.gsma.rcs.core.content.MmContent;
import com.gsma.rcs.core.ims.service.ImsServiceSession.InvitationStatus;
import com.gsma.rcs.core.ims.service.ImsServiceSession.TerminationReason;
import com.gsma.rcs.core.ims.service.im.InstantMessagingService;
import com.gsma.rcs.core.ims.service.im.filetransfer.FileSharingError;
import com.gsma.rcs.core.ims.service.im.filetransfer.FileSharingSession;
import com.gsma.rcs.core.ims.service.im.filetransfer.FileSharingSessionListener;
import com.gsma.rcs.core.ims.service.im.filetransfer.http.DownloadFromAcceptFileSharingSession;
import com.gsma.rcs.core.ims.service.im.filetransfer.http.DownloadFromResumeFileSharingSession;
import com.gsma.rcs.core.ims.service.im.filetransfer.http.HttpFileTransferSession;
import com.gsma.rcs.core.ims.service.im.filetransfer.http.ResumeUploadFileSharingSession;
import com.gsma.rcs.provider.contact.ContactManager;
import com.gsma.rcs.provider.fthttp.FtHttpResume;
import com.gsma.rcs.provider.fthttp.FtHttpResumeDownload;
import com.gsma.rcs.provider.fthttp.FtHttpResumeUpload;
import com.gsma.rcs.provider.messaging.FileTransferPersistedStorageAccessor;
import com.gsma.rcs.provider.messaging.FileTransferStateAndReasonCode;
import com.gsma.rcs.provider.messaging.MessagingLog;
import com.gsma.rcs.provider.settings.RcsSettings;
import com.gsma.rcs.provider.settings.RcsSettingsData.FileTransferProtocol;
import com.gsma.rcs.service.broadcaster.GroupFileTransferBroadcaster;
import com.gsma.rcs.service.broadcaster.IGroupFileTransferBroadcaster;
import com.gsma.rcs.utils.logger.Logger;
import com.gsma.services.rcs.RcsService.Direction;
import com.gsma.services.rcs.contact.ContactId;
import com.gsma.services.rcs.filetransfer.FileTransfer.ReasonCode;
import com.gsma.services.rcs.filetransfer.FileTransfer.State;
import com.gsma.services.rcs.filetransfer.IFileTransfer;

import android.net.Uri;
import android.os.RemoteException;

import javax2.sip.message.Response;

/**
 * File transfer implementation
 */
public class GroupFileTransferImpl extends IFileTransfer.Stub implements FileSharingSessionListener {

    private final String mFileTransferId;

    private final IGroupFileTransferBroadcaster mBroadcaster;

    private final InstantMessagingService mImService;

    private final FileTransferPersistedStorageAccessor mPersistentStorage;

    private final FileTransferServiceImpl mFileTransferService;

    private final RcsSettings mRcsSettings;

    private final Core mCore;

    private final MessagingLog mMessagingLog;

    private String mChatId;

    private final Object mLock = new Object();

    private final ContactManager mContactManager;

    private final static Logger sLogger = Logger.getLogger(GroupFileTransferImpl.class
            .getSimpleName());

    /**
     * Constructor
     * 
     * @param transferId Transfer ID
     * @param broadcaster IGroupFileTransferBroadcaster
     * @param imService InstantMessagingService
     * @param storageAccessor FileTransferPersistedStorageAccessor
     * @param fileTransferService FileTransferServiceImpl
     * @param rcsSettings RcsSettings
     * @param core Core
     * @param messagingLog
     * @param contactManager
     */
    public GroupFileTransferImpl(String transferId, IGroupFileTransferBroadcaster broadcaster,
            InstantMessagingService imService,
            FileTransferPersistedStorageAccessor storageAccessor,
            FileTransferServiceImpl fileTransferService, RcsSettings rcsSettings, Core core,
            MessagingLog messagingLog, ContactManager contactManager) {
        mFileTransferId = transferId;
        mBroadcaster = broadcaster;
        mImService = imService;
        mPersistentStorage = storageAccessor;
        mFileTransferService = fileTransferService;
        mRcsSettings = rcsSettings;
        mCore = core;
        mMessagingLog = messagingLog;
        mContactManager = contactManager;
    }

    /**
     * Constructor
     * 
     * @param transferId Transfer ID
     * @param chatId Chat Id
     * @param broadcaster IGroupFileTransferBroadcaster
     * @param imService InstantMessagingService
     * @param storageAccessor FileTransferPersistedStorageAccessor
     * @param fileTransferService FileTransferServiceImpl
     * @param rcsSettings RcsSettings
     * @param core Core
     * @param messagingLog
     * @param contactManager
     */
    public GroupFileTransferImpl(String transferId, String chatId,
            GroupFileTransferBroadcaster broadcaster, InstantMessagingService imService,
            FileTransferPersistedStorageAccessor storageAccessor,
            FileTransferServiceImpl fileTransferService, RcsSettings rcsSettings, Core core,
            MessagingLog messagingLog, ContactManager contactManager) {
        this(transferId, broadcaster, imService, storageAccessor, fileTransferService, rcsSettings,
                core, messagingLog, contactManager);
        mChatId = chatId;
    }

    private State getRcsState(FileSharingSession session) {
        HttpFileTransferSession.State state = ((HttpFileTransferSession) session).getSessionState();
        if (HttpFileTransferSession.State.ESTABLISHED == state) {
            if (isSessionPaused()) {
                return State.PAUSED;
            }
            return State.STARTED;
        } else if (session.isInitiatedByRemote()) {
            if (session.isSessionAccepted()) {
                return State.ACCEPTING;
            }
            return State.INVITED;
        }
        return State.INITIATING;
    }

    private ReasonCode getRcsReasonCode(FileSharingSession session) {
        if (isSessionPaused()) {
            /*
             * If session is paused and still established it must have been paused by user
             */
            return ReasonCode.PAUSED_BY_USER;
        }
        return ReasonCode.UNSPECIFIED;
    }

    /**
     * Returns the chat ID of the group chat
     * 
     * @return Chat ID
     * @throws RemoteException
     */
    public String getChatId() throws RemoteException {
        try {
            if (mChatId != null) {
                return mChatId;
            }
            return mPersistentStorage.getChatId();

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Returns the file transfer ID of the file transfer
     * 
     * @return Transfer ID
     * @throws RemoteException
     */
    public String getTransferId() throws RemoteException {
        try {
            return mFileTransferId;

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Returns the remote contact
     * 
     * @return Contact
     * @throws RemoteException
     */
    public ContactId getRemoteContact() throws RemoteException {
        try {
            FileSharingSession session = mImService.getFileSharingSession(mFileTransferId);
            if (session == null) {
                return mPersistentStorage.getRemoteContact();
            }
            return session.getRemoteContact();

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Returns the complete filename including the path of the file to be transferred
     * 
     * @return Filename
     * @throws RemoteException
     */
    public String getFileName() throws RemoteException {
        try {
            FileSharingSession session = mImService.getFileSharingSession(mFileTransferId);
            if (session == null) {
                return mPersistentStorage.getFileName();
            }
            return session.getContent().getName();

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Returns the Uri of the file to be transferred
     * 
     * @return Uri
     * @throws RemoteException
     */
    public Uri getFile() throws RemoteException {
        try {
            FileSharingSession session = mImService.getFileSharingSession(mFileTransferId);
            if (session == null) {
                return mPersistentStorage.getFile();
            }
            return session.getContent().getUri();

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Returns the size of the file to be transferred
     * 
     * @return Size in bytes
     * @throws RemoteException
     */
    public long getFileSize() throws RemoteException {
        try {
            FileSharingSession session = mImService.getFileSharingSession(mFileTransferId);
            if (session == null) {
                return mPersistentStorage.getFileSize();
            }
            return session.getContent().getSize();

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Returns the MIME type of the file to be transferred
     * 
     * @return Type
     * @throws RemoteException
     */
    public String getMimeType() throws RemoteException {
        try {
            FileSharingSession session = mImService.getFileSharingSession(mFileTransferId);
            if (session == null) {
                return mPersistentStorage.getMimeType();
            }
            return session.getContent().getEncoding();

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Returns the Uri of the file icon
     * 
     * @return Uri
     * @throws RemoteException
     */
    public Uri getFileIcon() throws RemoteException {
        try {
            FileSharingSession session = mImService.getFileSharingSession(mFileTransferId);
            if (session == null) {
                return mPersistentStorage.getFileIcon();
            }
            MmContent fileIcon = session.getContent();
            return fileIcon != null ? fileIcon.getUri() : null;

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Returns the Mime type of file icon
     * 
     * @return Mime type
     * @throws RemoteException
     */
    public String getFileIconMimeType() throws RemoteException {
        try {
            FileSharingSession session = mImService.getFileSharingSession(mFileTransferId);
            if (session == null) {
                return mPersistentStorage.getFileIconMimeType();
            }
            MmContent fileIconMimeType = session.getContent();
            return fileIconMimeType != null ? fileIconMimeType.getEncoding() : null;

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Returns the local timestamp of when the file transfer was initiated and/or queued for
     * outgoing file transfers or the local timestamp of when the file transfer invitation was
     * received for incoming file transfers
     * 
     * @return long
     * @throws RemoteException
     */
    public long getTimestamp() throws RemoteException {
        try {
            return mPersistentStorage.getTimestamp();

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Returns the local timestamp of when the file transfer was initiated and /or queued for
     * outgoing file transfers or the remote timestamp of when the file transfer was initiated for
     * incoming file transfers
     * 
     * @return long
     * @throws RemoteException
     */
    public long getTimestampSent() throws RemoteException {
        try {
            return mPersistentStorage.getTimestampSent();

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Returns the local timestamp of when the file transfer was delivered for outgoing file
     * transfers or 0 for incoming file transfers or it was not yet displayed
     * 
     * @return long
     * @throws RemoteException
     */
    public long getTimestampDelivered() throws RemoteException {
        try {
            return mPersistentStorage.getTimestampDelivered();

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Returns the local timestamp of when the file transfer was displayed for outgoing file
     * transfers or 0 for incoming file transfers or it was not yet displayed
     * 
     * @return long
     * @throws RemoteException
     */
    public long getTimestampDisplayed() throws RemoteException {
        try {
            return mPersistentStorage.getTimestampDisplayed();

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Returns the state of the file transfer
     * 
     * @return State
     * @throws RemoteException
     */
    public int getState() throws RemoteException {
        try {
            FileSharingSession session = mImService.getFileSharingSession(mFileTransferId);
            if (session == null) {
                return mPersistentStorage.getState().toInt();
            }
            return getRcsState(session).toInt();

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Returns the reason code of the state of the file transfer
     * 
     * @return ReasonCode
     * @throws RemoteException
     */
    public int getReasonCode() throws RemoteException {
        try {
            FileSharingSession session = mImService.getFileSharingSession(mFileTransferId);
            if (session == null) {
                return mPersistentStorage.getReasonCode().toInt();
            }
            return getRcsReasonCode(session).toInt();

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Returns the direction of the transfer (incoming or outgoing)
     * 
     * @return Direction
     * @throws RemoteException
     * @see Direction
     */
    public int getDirection() throws RemoteException {
        try {
            FileSharingSession session = mImService.getFileSharingSession(mFileTransferId);
            if (session == null) {
                return mPersistentStorage.getDirection().toInt();
            }
            if (session.isInitiatedByRemote()) {
                return Direction.INCOMING.toInt();
            }
            return Direction.OUTGOING.toInt();

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Accepts file transfer invitation
     * 
     * @throws RemoteException
     */
    public void acceptInvitation() throws RemoteException {
        try {
            if (sLogger.isActivated()) {
                sLogger.info("Accept session invitation");
            }
            final FileSharingSession ongoingSession = mImService
                    .getFileSharingSession(mFileTransferId);
            if (ongoingSession != null) {
                if (!ongoingSession.isInitiatedByRemote()) {
                    throw new ServerApiUnsupportedOperationException(new StringBuilder(
                            "Cannot accept transfer with fileTransferId '").append(mFileTransferId)
                            .append("': wrong direction").toString());

                }
                if (ongoingSession.isSessionAccepted()) {
                    throw new ServerApiPermissionDeniedException(new StringBuilder(
                            "Cannot accept transfer with fileTransferId '").append(mFileTransferId)
                            .append("': already accepted").toString());

                }
                ongoingSession.acceptSession();
                return;
            }
            /* No active session: restore session from provider */
            FtHttpResume resume = mPersistentStorage.getFileTransferResumeInfo();
            if (resume == null) {
                throw new ServerApiPersistentStorageException(
                        "Cannot find session with file transfer ID= ".concat(mFileTransferId));
            }
            if (!(resume instanceof FtHttpResumeDownload)) {
                throw new ServerApiUnsupportedOperationException(new StringBuilder(
                        "Cannot accept transfer with fileTransferId '").append(mFileTransferId)
                        .append("': wrong direction").toString());
            }
            FtHttpResumeDownload download = (FtHttpResumeDownload) resume;
            if (download.isAccepted()) {
                throw new ServerApiPermissionDeniedException(new StringBuilder(
                        "Cannot accept transfer with fileTransferId '").append(mFileTransferId)
                        .append("': already accepted").toString());
            }
            if (download.getFileExpiration() < System.currentTimeMillis()) {
                throw new ServerApiUnsupportedOperationException(new StringBuilder(
                        "Cannot accept transfer with fileTransferId '").append(mFileTransferId)
                        .append("': file has expired").toString());
            }
            FileSharingSession session = new DownloadFromAcceptFileSharingSession(mImService,
                    ContentManager.createMmContent(resume.getFile(), resume.getSize(),
                            resume.getFileName()), download, mRcsSettings, mMessagingLog,
                    mContactManager);
            session.addListener(this);
            session.startSession();
        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Rejects file transfer invitation
     * 
     * @throws RemoteException
     */
    public void rejectInvitation() throws RemoteException {
        try {
            if (sLogger.isActivated()) {
                sLogger.info("Reject session invitation");
            }
            final FileSharingSession session = mImService.getFileSharingSession(mFileTransferId);
            if (session == null) {
                throw new ServerApiGenericException(new StringBuilder(
                        "Session with file transfer ID '").append(mFileTransferId)
                        .append("' not available!").toString());
            }
            session.rejectSession(InvitationStatus.INVITATION_REJECTED_DECLINE);
        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Aborts the file transfer
     * 
     * @throws RemoteException
     */
    public void abortTransfer() throws RemoteException {
        try {
            if (sLogger.isActivated()) {
                sLogger.info("Cancel session");
            }
            final FileSharingSession session = mImService.getFileSharingSession(mFileTransferId);
            if (session == null) {
                /*
                 * File transfer can be aborted only if it is in state QUEUED/ PAUSED when there is
                 * no session.
                 */
                State state = mPersistentStorage.getState();
                switch (state) {
                    case QUEUED:
                    case PAUSED:
                        setStateAndReasonCode(State.ABORTED, ReasonCode.ABORTED_BY_SYSTEM);
                        return;
                    default:
                        throw new ServerApiPermissionDeniedException(new StringBuilder(
                                "Session with file transfer ID '").append(mFileTransferId)
                                .append("' not available!").toString());
                }
            }
            if (session.isFileTransfered()) {
                /* File already transferred and session automatically closed after transfer */
                throw new ServerApiPermissionDeniedException(
                        "Cannot abort as file is already transferred!");
            }
            new Thread() {
                public void run() {
                    session.terminateSession(TerminationReason.TERMINATION_BY_USER);
                }
            }.start();

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Is HTTP transfer
     * 
     * @return Boolean
     */
    public boolean isHttpTransfer() {
        /* Group file transfer is always a HTTP file transfer */
        return true;
    }

    /**
     * Returns true if it is possible to pause this file transfer right now, else returns false. If
     * this filetransfer corresponds to a file transfer that is no longer present in the persistent
     * storage false will be returned (this is no error)
     * 
     * @return boolean
     * @throws RemoteException
     */
    public boolean isAllowedToPauseTransfer() throws RemoteException {
        try {
            FileSharingSession session = mImService.getFileSharingSession(mFileTransferId);
            if (session == null) {
                if (sLogger.isActivated()) {
                    sLogger.debug(new StringBuilder("Cannot pause transfer with file transfer Id '")
                            .append(mFileTransferId)
                            .append("' as there is no ongoing session corresponding to the fileTransferId.")
                            .toString());
                }
                return false;
            }
            State state = getRcsState(session);
            if (State.STARTED != state) {
                if (sLogger.isActivated()) {
                    sLogger.debug(new StringBuilder("Cannot pause transfer with file transfer Id '")
                            .append(mFileTransferId).append("' as it is in state ").append(state)
                            .toString());
                }
                return false;
            }
            return true;

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Pauses the file transfer (only for HTTP transfer)
     * 
     * @throws RemoteException
     */
    public void pauseTransfer() throws RemoteException {
        try {
            if (!isAllowedToPauseTransfer()) {
                throw new ServerApiPermissionDeniedException("Not allowed to pause transfer.");
            }
            if (sLogger.isActivated()) {
                sLogger.info("Pause session");
            }
            FileSharingSession session = mImService.getFileSharingSession(mFileTransferId);
            ((HttpFileTransferSession) session).pauseFileTransfer();
        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Is session paused (only for HTTP transfer)
     */
    private boolean isSessionPaused() {
        FileSharingSession session = mImService.getFileSharingSession(mFileTransferId);
        if (session == null) {
            throw new ServerApiGenericException(new StringBuilder(
                    "Unable to check if transfer is paused since session with file transfer ID '")
                    .append(mFileTransferId).append("' not available!").toString());
        }
        return ((HttpFileTransferSession) session).isFileTransferPaused();
    }

    /**
     * Returns true if it is possible to resume this file transfer right now, else return false. If
     * this filetransfer corresponds to a file transfer that is no longer present in the persistent
     * storage false will be returned.
     * 
     * @return boolean
     * @throws RemoteException
     */
    public boolean isAllowedToResumeTransfer() throws RemoteException {
        try {
            ReasonCode reasonCode;
            FileSharingSession session = mImService.getFileSharingSession(mFileTransferId);
            if (session != null) {
                reasonCode = getRcsReasonCode(session);
            } else {
                reasonCode = mPersistentStorage.getReasonCode();
            }
            if (ReasonCode.PAUSED_BY_USER != reasonCode) {
                if (sLogger.isActivated()) {
                    sLogger.debug(new StringBuilder(
                            "Cannot resume transfer with file transfer Id '")
                            .append(mFileTransferId).append("' as it is ").append(reasonCode)
                            .toString());
                }
                return false;
            }
            if (!ServerApiUtils.isImsConnected()) {
                if (sLogger.isActivated()) {
                    sLogger.debug(new StringBuilder(
                            "Cannot resume transfer with file transfer Id '")
                            .append(mFileTransferId)
                            .append("' as it there is no IMS connection right now.").toString());
                }
                return false;
            }
            if (session == null) {
                if (!mImService.isFileTransferSessionAvailable()) {
                    if (sLogger.isActivated()) {
                        sLogger.debug(new StringBuilder(
                                "Cannot resume transfer with file transfer Id '")
                                .append(mFileTransferId)
                                .append("' as the limit of available file transfer session is reached.")
                                .toString());
                    }
                    return false;
                }
                if (Direction.OUTGOING == mPersistentStorage.getDirection()) {
                    if (mImService.isMaxConcurrentOutgoingFileTransfersReached()) {
                        if (sLogger.isActivated()) {
                            sLogger.debug(new StringBuilder(
                                    "Cannot resume transfer with file transfer Id '")
                                    .append(mFileTransferId)
                                    .append("' as the limit of maximum concurrent outgoing file transfer is reached.")
                                    .toString());
                        }
                        return false;
                    }
                }
            }
            return true;

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Resume the session (only for HTTP transfer)
     * 
     * @throws RemoteException
     */
    public void resumeTransfer() throws RemoteException {
        if (!isAllowedToResumeTransfer()) {
            throw new ServerApiPermissionDeniedException("Not allowed to resume transfer.");
        }
        try {
            FileSharingSession session = mImService.getFileSharingSession(mFileTransferId);
            if (session == null) {
                FtHttpResume resume = mPersistentStorage.getFileTransferResumeInfo();
                if (resume == null) {
                    throw new ServerApiPersistentStorageException(
                            "Unable to resume file with fileTransferId ".concat(mFileTransferId));
                }
                if (Direction.OUTGOING == mPersistentStorage.getDirection()) {
                    session = new ResumeUploadFileSharingSession(mImService,
                            ContentManager.createMmContent(resume.getFile(), resume.getSize(),
                                    resume.getFileName()), (FtHttpResumeUpload) resume,
                            mRcsSettings, mMessagingLog, mContactManager);
                } else {
                    session = new DownloadFromResumeFileSharingSession(mImService,
                            ContentManager.createMmContent(resume.getFile(), resume.getSize(),
                                    resume.getFileName()), (FtHttpResumeDownload) resume,
                            mRcsSettings, mMessagingLog, mContactManager);
                }
                session.addListener(this);
                session.startSession();
                return;
            }
            ((HttpFileTransferSession) session).resumeFileTransfer();
        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Returns whether you can resend the transfer.
     * 
     * @return boolean
     * @throws RemoteException
     */
    public boolean isAllowedToResendTransfer() throws RemoteException {
        /* Resend file transfer is supported only for one-to-one transfers */
        return false;
    }

    /**
     * Returns true if file transfer has been marked as read
     * 
     * @return boolean
     * @throws RemoteException
     */
    public boolean isRead() throws RemoteException {
        try {
            return mPersistentStorage.isRead();

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    /**
     * Resend a file transfer which was previously failed. This only for 1-1 file transfer, an
     * exception is thrown in case of a file transfer to group.
     * 
     * @throws RemoteException
     */
    public void resendTransfer() throws RemoteException {
        throw new ServerApiUnsupportedOperationException(
                "Resend operation not supported for group file transfer with file transfer ID "
                        .concat(mFileTransferId));
    }

    /*------------------------------- SESSION EVENTS ----------------------------------*/

    /**
     * Session is started
     * 
     * @param contact
     */
    public void handleSessionStarted(ContactId contact) {
        if (sLogger.isActivated()) {
            sLogger.info("Session started");
        }
        synchronized (mLock) {
            setStateAndReasonCode(State.STARTED, ReasonCode.UNSPECIFIED);
        }
    }

    /**
     * Handle file info dequeued
     */
    public void handleFileInfoDequeued() {
        if (sLogger.isActivated()) {
            sLogger.info(new StringBuilder("Group file info with transferId ")
                    .append(mFileTransferId).append(" dequeued successfully.").toString());
        }
        synchronized (mLock) {
            mFileTransferService.removeGroupFileTransfer(mFileTransferId);
            setStateAndReasonCode(State.TRANSFERRED, ReasonCode.UNSPECIFIED);
        }
        mCore.getListener().tryToDequeueFileTransfers(mCore);
    }

    /*
     * TODO : Fix reasoncode mapping in the switch.
     */
    private FileTransferStateAndReasonCode toStateAndReasonCode(FileSharingError error) {
        int fileSharingError = error.getErrorCode();
        switch (fileSharingError) {
            case FileSharingError.SESSION_INITIATION_DECLINED:
            case FileSharingError.SESSION_INITIATION_CANCELLED:
                return new FileTransferStateAndReasonCode(State.REJECTED,
                        ReasonCode.REJECTED_BY_REMOTE);
            case FileSharingError.MEDIA_SAVING_FAILED:
                return new FileTransferStateAndReasonCode(State.FAILED, ReasonCode.FAILED_SAVING);
            case FileSharingError.MEDIA_SIZE_TOO_BIG:
                return new FileTransferStateAndReasonCode(State.REJECTED,
                        ReasonCode.REJECTED_MAX_SIZE);
            case FileSharingError.MEDIA_TRANSFER_FAILED:
            case FileSharingError.MEDIA_UPLOAD_FAILED:
            case FileSharingError.MEDIA_DOWNLOAD_FAILED:
                return new FileTransferStateAndReasonCode(State.FAILED,
                        ReasonCode.FAILED_DATA_TRANSFER);
            case FileSharingError.NO_CHAT_SESSION:
            case FileSharingError.SESSION_INITIATION_FAILED:
                return new FileTransferStateAndReasonCode(State.FAILED,
                        ReasonCode.FAILED_INITIATION);
            case FileSharingError.NOT_ENOUGH_STORAGE_SPACE:
                return new FileTransferStateAndReasonCode(State.REJECTED,
                        ReasonCode.REJECTED_LOW_SPACE);
            default:
                throw new IllegalArgumentException(
                        new StringBuilder(
                                "Unknown reason in GroupFileTransferImpl.toStateAndReasonCode; fileSharingError=")
                                .append(fileSharingError).append("!").toString());
        }
    }

    private void handleSessionRejected(ReasonCode reasonCode, ContactId contact) {
        if (sLogger.isActivated()) {
            sLogger.info("Session rejected; reasonCode=" + reasonCode + ".");
        }
        synchronized (mLock) {
            mFileTransferService.removeGroupFileTransfer(mFileTransferId);

            setStateAndReasonCode(State.REJECTED, reasonCode);
        }
        mCore.getListener().tryToDequeueFileTransfers(mCore);
    }

    private void setStateAndReasonCode(State state, ReasonCode reasonCode) {
        if (mPersistentStorage.setStateAndReasonCode(state, reasonCode)) {
            mBroadcaster.broadcastStateChanged(mChatId, mFileTransferId, state, reasonCode);
        }
    }

    /**
     * Session has been aborted
     * 
     * @param contact
     * @param reason Termination reason
     */
    public void handleSessionAborted(ContactId contact, TerminationReason reason) {
        if (sLogger.isActivated()) {
            sLogger.info(new StringBuilder("Session aborted (reason ").append(reason).append(")")
                    .toString());
        }
        synchronized (mLock) {
            mFileTransferService.removeGroupFileTransfer(mFileTransferId);
            switch (reason) {
                case TERMINATION_BY_TIMEOUT:
                case TERMINATION_BY_SYSTEM:
                    setStateAndReasonCode(State.ABORTED, ReasonCode.ABORTED_BY_SYSTEM);
                    break;
                case TERMINATION_BY_CONNECTION_LOST:
                    setStateAndReasonCode(State.FAILED, ReasonCode.FAILED_DATA_TRANSFER);
                    break;
                case TERMINATION_BY_USER:
                    setStateAndReasonCode(State.ABORTED, ReasonCode.ABORTED_BY_USER);
                    break;
                case TERMINATION_BY_REMOTE:
                    /*
                     * TODO : Fix sending of SIP BYE by sender once transfer is completed and media
                     * session is closed. Then this check of state can be removed. Also need to
                     * check if it is storing and broadcasting right state and reasoncode.
                     */
                    if (State.TRANSFERRED != mPersistentStorage.getState()) {
                        setStateAndReasonCode(State.ABORTED, ReasonCode.ABORTED_BY_REMOTE);
                    }
                    break;
                default:
                    throw new IllegalArgumentException(
                            new StringBuilder(
                                    "Unknown reason in GroupFileTransferImpl.handleSessionAborted; terminationReason=")
                                    .append(reason).append("!").toString());
            }
        }
        mCore.getListener().tryToDequeueFileTransfers(mCore);
    }

    /**
     * Session has been terminated by remote
     * 
     * @param contact
     */
    public void handleSessionTerminatedByRemote(ContactId contact) {
        if (sLogger.isActivated()) {
            sLogger.info("Session terminated by remote");
        }
        synchronized (mLock) {
            mFileTransferService.removeGroupFileTransfer(mFileTransferId);
            /*
             * TODO : Fix sending of SIP BYE by sender once transfer is completed and media session
             * is closed. Then this check of state can be removed. Also need to check if it is
             * storing and broadcasting right state and reasoncode.
             */
            if (State.TRANSFERRED != mPersistentStorage.getState()) {
                setStateAndReasonCode(State.ABORTED, ReasonCode.ABORTED_BY_REMOTE);
            }
        }
        mCore.getListener().tryToDequeueFileTransfers(mCore);
    }

    /**
     * File transfer error
     * 
     * @param error Error
     * @param contact
     */
    public void handleTransferError(FileSharingError error, ContactId contact) {
        if (sLogger.isActivated()) {
            sLogger.info("Sharing error " + error.getErrorCode());
        }
        FileTransferStateAndReasonCode stateAndReasonCode = toStateAndReasonCode(error);
        State state = stateAndReasonCode.getState();
        ReasonCode reasonCode = stateAndReasonCode.getReasonCode();
        synchronized (mLock) {
            mFileTransferService.removeGroupFileTransfer(mFileTransferId);
            setStateAndReasonCode(state, reasonCode);
        }
        mCore.getListener().tryToDequeueFileTransfers(mCore);
    }

    /**
     * File transfer progress
     * 
     * @param contact
     * @param currentSize Data size transferred
     * @param totalSize Total size to be transferred
     */
    public void handleTransferProgress(ContactId contact, long currentSize, long totalSize) {
        synchronized (mLock) {
            if (mPersistentStorage.setProgress(currentSize)) {
                mBroadcaster.broadcastProgressUpdate(mChatId, mFileTransferId, currentSize,
                        totalSize);
            }
        }
    }

    /**
     * File transfer not allowed to send
     */
    @Override
    public void handleTransferNotAllowedToSend(ContactId contact) {
        synchronized (mLock) {
            setStateAndReasonCode(State.FAILED, ReasonCode.FAILED_NOT_ALLOWED_TO_SEND);
        }
        mCore.getListener().tryToDequeueFileTransfers(mCore);
    }

    /**
     * File has been transfered
     * 
     * @param content MmContent associated to the received file
     * @param contact
     * @param fileExpiration the time when the file on the content server is no longer valid to
     *            download.
     * @param fileIconExpiration the time when the file icon on the content server is no longer
     *            valid to download.
     * @param ftProtocol
     */
    public void handleFileTransfered(MmContent content, ContactId contact, long fileExpiration,
            long fileIconExpiration, FileTransferProtocol ftProtocol) {
        if (sLogger.isActivated()) {
            sLogger.info("Content transferred");
        }
        synchronized (mLock) {
            mFileTransferService.removeGroupFileTransfer(mFileTransferId);
            long deliveryExpiration = 0;
            if (mPersistentStorage.setTransferred(content, fileExpiration, fileIconExpiration,
                    deliveryExpiration)) {
                mBroadcaster.broadcastStateChanged(mChatId, mFileTransferId, State.TRANSFERRED,
                        ReasonCode.UNSPECIFIED);
            }
        }
        mCore.getListener().tryToDequeueFileTransfers(mCore);
    }

    /**
     * File transfer has been paused by user
     */
    @Override
    public void handleFileTransferPausedByUser(ContactId contact) {
        if (sLogger.isActivated()) {
            sLogger.info("Transfer paused by user");
        }
        synchronized (mLock) {
            setStateAndReasonCode(State.PAUSED, ReasonCode.PAUSED_BY_USER);
        }
    }

    /**
     * File transfer has been paused by system
     */
    @Override
    public void handleFileTransferPausedBySystem(ContactId contact) {
        if (sLogger.isActivated()) {
            sLogger.info("Transfer paused by system");
        }
        synchronized (mLock) {
            mFileTransferService.removeGroupFileTransfer(mFileTransferId);
            setStateAndReasonCode(State.PAUSED, ReasonCode.PAUSED_BY_SYSTEM);
        }
    }

    /**
     * File transfer has been resumed
     * 
     * @param contact
     */
    public void handleFileTransferResumed(ContactId contact) {
        if (sLogger.isActivated()) {
            sLogger.info("Transfer resumed");
        }
        synchronized (mLock) {
            setStateAndReasonCode(State.STARTED, ReasonCode.UNSPECIFIED);
        }
    }

    @Override
    public void handleSessionAccepted(ContactId contact) {
        if (sLogger.isActivated()) {
            sLogger.info("Accepting transfer");
        }
        synchronized (mLock) {
            setStateAndReasonCode(State.ACCEPTING, ReasonCode.UNSPECIFIED);
        }
    }

    @Override
    public void handleSessionRejected(ContactId contact, TerminationReason reason) {
        switch (reason) {
            case TERMINATION_BY_USER:
                handleSessionRejected(ReasonCode.REJECTED_BY_USER, contact);
                break;
            case TERMINATION_BY_SYSTEM:
                /* Intentional fall through */
            case TERMINATION_BY_CONNECTION_LOST:
                handleSessionRejected(ReasonCode.REJECTED_BY_SYSTEM, contact);
                break;
            case TERMINATION_BY_TIMEOUT:
                handleSessionRejected(ReasonCode.REJECTED_BY_TIMEOUT, contact);
                break;
            case TERMINATION_BY_REMOTE:
                handleSessionRejected(ReasonCode.REJECTED_BY_REMOTE, contact);
                break;
            default:
                throw new IllegalArgumentException(new StringBuilder(
                        "Unknown reason RejectedReason=").append(reason).append("!").toString());
        }
    }

    @Override
    public void handleSessionInvited(ContactId contact, MmContent file, MmContent fileIcon,
            long timestamp, long timestampSent, long fileExpiration, long fileIconExpiration) {
        if (sLogger.isActivated()) {
            sLogger.info("Invited to group file transfer session");
        }
        synchronized (mLock) {
            mPersistentStorage.addIncomingGroupFileTransfer(mChatId, contact, file, fileIcon,
                    State.INVITED, ReasonCode.UNSPECIFIED, timestamp, timestampSent,
                    fileExpiration, fileIconExpiration);
        }

        mBroadcaster.broadcastInvitation(mFileTransferId);
    }

    @Override
    public void handleSessionAutoAccepted(ContactId contact, MmContent file, MmContent fileIcon,
            long timestamp, long timestampSent, long fileExpiration, long fileIconExpiration) {
        if (sLogger.isActivated()) {
            sLogger.info("Session auto accepted");
        }
        synchronized (mLock) {
            mPersistentStorage.addIncomingGroupFileTransfer(mChatId, contact, file, fileIcon,
                    State.ACCEPTING, ReasonCode.UNSPECIFIED, timestamp, timestampSent,
                    fileExpiration, fileIconExpiration);
        }

        mBroadcaster.broadcastInvitation(mFileTransferId);
    }

    @Override
    public long getFileExpiration() throws RemoteException {
        try {
            FileSharingSession session = mImService.getFileSharingSession(mFileTransferId);
            if (session == null) {
                return mPersistentStorage.getFileExpiration();
            }
            return session.getFileExpiration();

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    @Override
    public long getFileIconExpiration() throws RemoteException {
        try {
            FileSharingSession session = mImService.getFileSharingSession(mFileTransferId);
            if (session == null) {
                return mPersistentStorage.getFileIconExpiration();
            }
            return session.getIconExpiration();

        } catch (ServerApiBaseException e) {
            if (!e.shouldNotBeLogged()) {
                sLogger.error(ExceptionUtil.getFullStackTrace(e));
            }
            throw e;

        } catch (Exception e) {
            sLogger.error(ExceptionUtil.getFullStackTrace(e));
            throw new ServerApiGenericException(e);
        }
    }

    @Override
    public boolean isExpiredDelivery() throws RemoteException {
        /* Delivery expiration is not applicable for group file transfers. */
        return false;
    }
}
