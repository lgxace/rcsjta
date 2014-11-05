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

package com.orangelabs.rcs.core.ims.service.richcall.video;

import java.util.Collection;
import java.util.Vector;

import com.gsma.services.rcs.contacts.ContactId;
import com.gsma.services.rcs.vsh.VideoCodec;
import com.orangelabs.rcs.core.content.ContentManager;
import com.orangelabs.rcs.core.ims.network.sip.SipMessageFactory;
import com.orangelabs.rcs.core.ims.protocol.sdp.MediaDescription;
import com.orangelabs.rcs.core.ims.protocol.sdp.SdpParser;
import com.orangelabs.rcs.core.ims.protocol.sdp.SdpUtils;
import com.orangelabs.rcs.core.ims.protocol.sip.SipRequest;
import com.orangelabs.rcs.core.ims.protocol.sip.SipResponse;
import com.orangelabs.rcs.core.ims.protocol.sip.SipTransactionContext;
import com.orangelabs.rcs.core.ims.service.ImsService;
import com.orangelabs.rcs.core.ims.service.ImsServiceSession;
import com.orangelabs.rcs.core.ims.service.ImsSessionListener;
import com.orangelabs.rcs.core.ims.service.SessionTimerManager;
import com.orangelabs.rcs.core.ims.service.richcall.ContentSharingError;
import com.orangelabs.rcs.core.ims.service.richcall.RichcallService;
import com.orangelabs.rcs.utils.logger.Logger;

/**
 * Terminating live video content sharing session (streaming)
 *
 * @author Jean-Marc AUFFRET
 */
public class TerminatingVideoStreamingSession extends VideoStreamingSession {
    /**
     * The logger
     */
    private final static Logger logger = Logger.getLogger(TerminatingVideoStreamingSession.class.getSimpleName());

    /**
     * Constructor
     *
     * @param parent IMS service
     * @param invite Initial INVITE request
     * @param contact Contact Id
     */
	public TerminatingVideoStreamingSession(ImsService parent, SipRequest invite, ContactId contact) {
		super(parent, ContentManager.createLiveVideoContentFromSdp(invite.getContentBytes()), contact);

		// Create dialog path
		createTerminatingDialogPath(invite);
	}

    /**
     * Background processing
     */
    public void run() {
        try {
            if (logger.isActivated()) {
                logger.info("Initiate a new live video sharing session as terminating");
            }

            // Send a 180 Ringing response
            send180Ringing(getDialogPath().getInvite(), getDialogPath().getLocalTag());

            // Parse the remote SDP part
            SdpParser parser = new SdpParser(getDialogPath().getRemoteContent().getBytes());
            MediaDescription mediaVideo = parser.getMediaDescription("video");
            String remoteHost = SdpUtils.extractRemoteHost(parser.sessionDescription, mediaVideo);
            int remotePort = mediaVideo.port;

            // Extract video codecs from SDP
            Vector<MediaDescription> medias = parser.getMediaDescriptions("video");
            Vector<VideoCodec> proposedCodecs = VideoCodecManager.extractVideoCodecsFromSdp(medias);

            // Notify listener
            Collection<ImsSessionListener> listeners = getListeners();
            for (ImsSessionListener listener : listeners) {
                listener.handleSessionInvited();
            }

            // Wait invitation answer
            int answer = waitInvitationAnswer();
            switch (answer) {
                case ImsServiceSession.INVITATION_REJECTED:
                    if (logger.isActivated()) {
                        logger.debug("Session has been rejected by user");
                    }

                    getImsService().removeSession(this);

                    for (ImsSessionListener listener : listeners) {
                        listener.handleSessionRejectedByUser();
                    }
                    return;

                case ImsServiceSession.INVITATION_NOT_ANSWERED:
                    if (logger.isActivated()) {
                        logger.debug("Session has been rejected on timeout");
                    }

                    // Ringing period timeout
                    send486Busy(getDialogPath().getInvite(), getDialogPath().getLocalTag());

                    getImsService().removeSession(this);

                    for (ImsSessionListener listener : listeners) {
                        listener.handleSessionRejectedByTimeout();
                    }
                    return;

                case ImsServiceSession.INVITATION_CANCELED:
                    if (logger.isActivated()) {
                        logger.debug("Session has been rejected by remote");
                    }

                    getImsService().removeSession(this);

                    for (ImsSessionListener listener : listeners) {
                        listener.handleSessionRejectedByRemote();
                    }
                    return;

                case ImsServiceSession.INVITATION_ACCEPTED:
                    setSessionAccepted();

                    for (ImsSessionListener listener : listeners) {
                        listener.handleSessionAccepted();
                    }
                    break;

                default:
                    if (logger.isActivated()) {
                        logger.debug("Unknown invitation answer in run; answer="
                                .concat(String.valueOf(answer)));
                    }
                    return;
            }

            // Check that a video player has been set
            if (getVideoPlayer() == null) {
                handleError(new ContentSharingError(
                        ContentSharingError.MEDIA_PLAYER_NOT_INITIALIZED));
                return;
            }

            // Codec negotiation
            VideoCodec selectedVideoCodec = VideoCodecManager.negociateVideoCodec(
            		getVideoPlayer().getSupportedCodecs(), proposedCodecs);
            if (selectedVideoCodec == null) {
                if (logger.isActivated()){
                    logger.debug("Proposed codecs are not supported");
                }
                
                // Send a 415 Unsupported media type response
                send415Error(getDialogPath().getInvite());
                
                // Unsupported media type
                handleError(new ContentSharingError(ContentSharingError.UNSUPPORTED_MEDIA_TYPE));
                return;
            }

            // Set the OrientationHeaderID
            SdpOrientationExtension extensionHeader = SdpOrientationExtension.create(mediaVideo);
            if (extensionHeader != null) {
            	// TODO getVideoRenderer().setOrientationHeaderId(extensionHeader.getExtensionId());
            }

            // Build SDP part
	    	String ipAddress = getDialogPath().getSipStack().getLocalIpAddress();
            String videoSdp = VideoSdpBuilder.buildSdpAnswer(selectedVideoCodec, getVideoPlayer().getLocalRtpPort(), mediaVideo); 
            String sdp = SdpUtils.buildVideoSDP(ipAddress, videoSdp, SdpUtils.DIRECTION_RECVONLY);

            // Set the local SDP part in the dialog path
            getDialogPath().setLocalContent(sdp);

	        // Test if the session should be interrupted
            if (isInterrupted()) {
            	if (logger.isActivated()) {
            		logger.debug("Session has been interrupted: end of processing");
            	}
            	return;
            }

            // Create a 200 OK response
            if (logger.isActivated()) {
                logger.info("Send 200 OK");
            }
            SipResponse resp = SipMessageFactory.create200OkInviteResponse(getDialogPath(),
                    RichcallService.FEATURE_TAGS_VIDEO_SHARE, sdp);

            // The signalisation is established
            getDialogPath().sigEstablished();

            // Send response
            SipTransactionContext ctx = getImsService().getImsModule().getSipManager().sendSipMessageAndWait(resp);

            // Analyze the received response
            if (ctx.isSipAck()) {
                // ACK received
                if (logger.isActivated()) {
                    logger.info("ACK request received");
                }

                // The session is established
                getDialogPath().sessionEstablished();

                // Start session timer
                if (getSessionTimerManager().isSessionTimerActivated(resp)) {
                    getSessionTimerManager().start(SessionTimerManager.UAS_ROLE, getDialogPath().getSessionExpireTime());
                }

                // Set the video player remote info
                getVideoPlayer().setRemoteInfo(selectedVideoCodec, remoteHost, remotePort);

                // Notify listeners
                for(int i=0; i < getListeners().size(); i++) {
                    getListeners().get(i).handleSessionStarted();
                }
            } else {
                if (logger.isActivated()) {
                    logger.debug("No ACK received for INVITE");
                }

                // No response received: timeout
                handleError(new ContentSharingError(ContentSharingError.SESSION_INITIATION_FAILED));
            }
        } catch(Exception e) {
            if (logger.isActivated()) {
                logger.error("Session initiation has failed", e);
            }

            // Unexpected error
            handleError(new ContentSharingError(ContentSharingError.UNEXPECTED_EXCEPTION,
                    e.getMessage()));
        }
    }

    /**
     * Handle error
     *
     * @param error Error
     */
    public void handleError(ContentSharingError error) {
        if (isSessionInterrupted()) {
            return;
        }

        // Error
        if (logger.isActivated()) {
            logger.info("Session error: " + error.getErrorCode() + ", reason=" + error.getMessage());
        }

        // Close media session
        closeMediaSession();

        // Remove the current session
        getImsService().removeSession(this);

        // Notify listener
        for(int i=0; i < getListeners().size(); i++) {
            ((VideoStreamingSessionListener)getListeners().get(i)).handleSharingError(error);
        }
    }

    /**
     * Prepare media session
     * 
     * @throws Exception 
     */
    public void prepareMediaSession() throws Exception {
          // Nothing to do in terminating side
    }

    /**
     * Start media session
     * 
     * @throws Exception 
     */
    public void startMediaSession() throws Exception {
        // Nothing to do in terminating side
    }
    
	/**
	 * Close media session
	 */
	public void closeMediaSession() {    
		// Nothing to do in case of external codec
	}    

	@Override
	public boolean isInitiatedByRemote() {
		return true;
	}
}

