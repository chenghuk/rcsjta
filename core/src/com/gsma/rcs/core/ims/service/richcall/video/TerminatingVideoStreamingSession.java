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

package com.gsma.rcs.core.ims.service.richcall.video;

import static com.gsma.rcs.utils.StringUtils.UTF8;

import com.gsma.rcs.core.content.ContentManager;
import com.gsma.rcs.core.content.MmContent;
import com.gsma.rcs.core.ims.network.sip.SipMessageFactory;
import com.gsma.rcs.core.ims.protocol.sdp.MediaDescription;
import com.gsma.rcs.core.ims.protocol.sdp.SdpParser;
import com.gsma.rcs.core.ims.protocol.sdp.SdpUtils;
import com.gsma.rcs.core.ims.protocol.sip.SipDialogPath;
import com.gsma.rcs.core.ims.protocol.sip.SipException;
import com.gsma.rcs.core.ims.protocol.sip.SipRequest;
import com.gsma.rcs.core.ims.protocol.sip.SipResponse;
import com.gsma.rcs.core.ims.protocol.sip.SipTransactionContext;
import com.gsma.rcs.core.ims.service.ImsService;
import com.gsma.rcs.core.ims.service.ImsSessionListener;
import com.gsma.rcs.core.ims.service.SessionTimerManager;
import com.gsma.rcs.core.ims.service.richcall.ContentSharingError;
import com.gsma.rcs.core.ims.service.richcall.RichcallService;
import com.gsma.rcs.provider.contact.ContactManager;
import com.gsma.rcs.provider.settings.RcsSettings;
import com.gsma.rcs.utils.logger.Logger;
import com.gsma.services.rcs.contact.ContactId;
import com.gsma.services.rcs.sharing.video.IVideoPlayer;
import com.gsma.services.rcs.sharing.video.VideoCodec;

import android.os.RemoteException;

import java.util.Collection;
import java.util.Vector;

/**
 * Terminating live video content sharing session (streaming)
 * 
 * @author Jean-Marc AUFFRET
 */
public class TerminatingVideoStreamingSession extends VideoStreamingSession {

    /**
     * The logger
     */
    private final Logger mLogger = Logger.getLogger(getClass().getSimpleName());

    /**
     * Constructor
     * 
     * @param parent IMS service
     * @param invite Initial INVITE request
     * @param contact Contact Id
     * @param rcsSettings
     * @param timestamp Local timestamp for the session
     * @param contactManager
     */
    public TerminatingVideoStreamingSession(ImsService parent, SipRequest invite,
            ContactId contact, RcsSettings rcsSettings, long timestamp,
            ContactManager contactManager) {
        super(parent, ContentManager.createLiveVideoContentFromSdp(invite.getContentBytes()),
                contact, rcsSettings, timestamp, contactManager);

        // Create dialog path
        createTerminatingDialogPath(invite);
    }

    /**
     * Background processing
     */
    public void run() {
        try {
            if (mLogger.isActivated()) {
                mLogger.info("Initiate a new live video sharing session as terminating");
            }
            SipDialogPath dialogPath = getDialogPath();
            // Send a 180 Ringing response
            send180Ringing(dialogPath.getInvite(), dialogPath.getLocalTag());

            // Parse the remote SDP part
            SdpParser parser = new SdpParser(getDialogPath().getRemoteContent().getBytes(UTF8));
            MediaDescription mediaVideo = parser.getMediaDescription("video");
            String remoteHost = SdpUtils.extractRemoteHost(parser.sessionDescription, mediaVideo);
            int remotePort = mediaVideo.port;

            // Extract video codecs from SDP
            Vector<MediaDescription> medias = parser.getMediaDescriptions("video");
            Vector<VideoCodec> proposedCodecs = VideoCodecManager.extractVideoCodecsFromSdp(medias);

            // Notify listener
            Collection<ImsSessionListener> listeners = getListeners();
            ContactId contact = getRemoteContact();
            MmContent content = getContent();
            long timestamp = getTimestamp();
            for (ImsSessionListener listener : listeners) {
                ((VideoStreamingSessionListener) listener).handleSessionInvited(contact, content,
                        timestamp);
            }

            // Wait invitation answer
            InvitationStatus answer = waitInvitationAnswer();
            switch (answer) {
                case INVITATION_REJECTED:
                    if (mLogger.isActivated()) {
                        mLogger.debug("Session has been rejected by user");
                    }

                    removeSession();

                    for (ImsSessionListener listener : listeners) {
                        listener.handleSessionRejected(contact,
                                TerminationReason.TERMINATION_BY_USER);
                    }
                    return;

                case INVITATION_TIMEOUT:
                    if (mLogger.isActivated()) {
                        mLogger.debug("Session has been rejected on timeout");
                    }

                    // Ringing period timeout
                    send486Busy(dialogPath.getInvite(), dialogPath.getLocalTag());

                    removeSession();

                    for (ImsSessionListener listener : listeners) {
                        listener.handleSessionRejected(contact,
                                TerminationReason.TERMINATION_BY_TIMEOUT);
                    }
                    return;

                case INVITATION_REJECTED_BY_SYSTEM:
                    if (mLogger.isActivated()) {
                        mLogger.debug("Session has been aborted by system");
                    }
                    removeSession();
                    return;

                case INVITATION_CANCELED:
                    if (mLogger.isActivated()) {
                        mLogger.debug("Session has been rejected by remote");
                    }

                    removeSession();

                    for (ImsSessionListener listener : listeners) {
                        listener.handleSessionRejected(contact,
                                TerminationReason.TERMINATION_BY_REMOTE);
                    }
                    return;

                case INVITATION_ACCEPTED:
                    setSessionAccepted();

                    for (ImsSessionListener listener : listeners) {
                        listener.handleSessionAccepted(contact);
                    }
                    break;

                case INVITATION_DELETED:
                    if (mLogger.isActivated()) {
                        mLogger.debug("Session has been deleted");
                    }
                    removeSession();
                    return;

                default:
                    if (mLogger.isActivated()) {
                        mLogger.debug("Unknown invitation answer in run; answer=".concat(String
                                .valueOf(answer)));
                    }
                    return;
            }

            IVideoPlayer player = getPlayer();
            // Check that a video player has been set
            if (player == null) {
                handleError(new ContentSharingError(
                        ContentSharingError.MEDIA_PLAYER_NOT_INITIALIZED));
                return;
            }

            // Codec negotiation
            VideoCodec selectedVideoCodec = VideoCodecManager.negociateVideoCodec(
                    player.getSupportedCodecs(), proposedCodecs);
            if (selectedVideoCodec == null) {
                if (mLogger.isActivated()) {
                    mLogger.debug("Proposed codecs are not supported");
                }

                // Send a 415 Unsupported media type response
                send415Error(dialogPath.getInvite());

                // Unsupported media type
                handleError(new ContentSharingError(ContentSharingError.UNSUPPORTED_MEDIA_TYPE));
                return;
            }

            // Set the video player orientation
            SdpOrientationExtension extensionHeader = SdpOrientationExtension.create(mediaVideo);
            if (extensionHeader != null) {
                // Update the orientation ID
                setOrientation(extensionHeader.getExtensionId());
            }

            // Build SDP part
            // Note ID_6_5 Extmap: it is recommended not to change the extmap's local
            // identifier in the SDP answer from the one in the SDP offer because there
            // are no reasons to do that since there should only be one extension in use.
            String ipAddress = dialogPath.getSipStack().getLocalIpAddress();
            String videoSdp = VideoSdpBuilder.buildSdpAnswer(selectedVideoCodec,
                    player.getLocalRtpPort(), mediaVideo);
            String sdp = SdpUtils.buildVideoSDP(ipAddress, videoSdp, SdpUtils.DIRECTION_RECVONLY);

            // Set the local SDP part in the dialog path
            dialogPath.setLocalContent(sdp);

            // Test if the session should be interrupted
            if (isInterrupted()) {
                if (mLogger.isActivated()) {
                    mLogger.debug("Session has been interrupted: end of processing");
                }
                return;
            }

            // Create a 200 OK response
            if (mLogger.isActivated()) {
                mLogger.info("Send 200 OK");
            }
            SipResponse resp = SipMessageFactory.create200OkInviteResponse(dialogPath,
                    RichcallService.FEATURE_TAGS_VIDEO_SHARE, sdp);

            // The signalisation is established
            dialogPath.sigEstablished();

            // Send response
            SipTransactionContext ctx = getImsService().getImsModule().getSipManager()
                    .sendSipMessageAndWait(resp);
            
            // Analyze the received response
            if (ctx.isSipAck()) {
                // ACK received
                if (mLogger.isActivated()) {
                    mLogger.info("ACK request received");
                }

                // The session is established
                dialogPath.sessionEstablished();

                // Start session timer
                SessionTimerManager sessionTimerManager = getSessionTimerManager();
                if (sessionTimerManager.isSessionTimerActivated(resp)) {
                    sessionTimerManager.start(SessionTimerManager.UAS_ROLE,
                            dialogPath.getSessionExpireTime());
                }

                // Set the video player remote info
                player.setRemoteInfo(selectedVideoCodec, remoteHost, remotePort, getOrientation());

                for (ImsSessionListener listener : listeners) {
                    listener.handleSessionStarted(contact);
                }
            } else {
                if (mLogger.isActivated()) {
                    mLogger.debug("No ACK received for INVITE");
                }

                // No response received: timeout
                handleError(new ContentSharingError(ContentSharingError.SEND_RESPONSE_FAILED));
            }
        } catch (RemoteException e) {
            mLogger.error("Failed to set remote info!", e);
            handleError(new ContentSharingError(ContentSharingError.SESSION_INITIATION_FAILED, e));
        } catch (SipException e) {
            mLogger.error("Failed to send 200OK response!", e);
            handleError(new ContentSharingError(ContentSharingError.SEND_RESPONSE_FAILED, e));
        } catch (RuntimeException e) {
            /*
             * Intentionally catch runtime exceptions as else it will abruptly end the thread and
             * eventually bring the whole system down, which is not intended.
             */
            mLogger.error("Failed initiate a new live video sharing session as terminating!", e);
            handleError(new ContentSharingError(ContentSharingError.SESSION_INITIATION_FAILED, e));
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
        if (mLogger.isActivated()) {
            mLogger.info(new StringBuilder("Session error: ")
                    .append(String.valueOf(error.getErrorCode())).append(", reason=")
                    .append(error.getMessage()).toString());
        }

        // Close media session
        closeMediaSession();

        // Remove the current session
        removeSession();

        ContactId contact = getRemoteContact();
        for (ImsSessionListener listener : getListeners()) {
            ((VideoStreamingSessionListener) listener).handleSharingError(contact, error);
        }
    }

    /**
     * Prepare media session
     */
    public void prepareMediaSession() {
        /* Nothing to do in case of external codec */
    }

    /**
     * Open media session
     */
    public void openMediaSession() {
        /* Nothing to do in case of external codec */
    }

    /**
     * Start media transfer
     */
    public void startMediaTransfer() {
        /* Nothing to do in case of external codec */
    }

    /**
     * Close media session
     */
    public void closeMediaSession() {
        /* Nothing to do in case of external codec */
    }

    @Override
    public boolean isInitiatedByRemote() {
        return true;
    }
}
