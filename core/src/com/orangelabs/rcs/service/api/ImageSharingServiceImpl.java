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
package com.orangelabs.rcs.service.api;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Hashtable;
import java.util.List;

import android.content.Intent;
import android.net.Uri;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteCallbackList;

import com.gsma.services.rcs.IJoynServiceRegistrationListener;
import com.gsma.services.rcs.JoynService;
import com.gsma.services.rcs.contacts.ContactId;
import com.gsma.services.rcs.ish.IImageSharing;
import com.gsma.services.rcs.ish.IImageSharingListener;
import com.gsma.services.rcs.ish.IImageSharingService;
import com.gsma.services.rcs.ish.ImageSharing;
import com.gsma.services.rcs.ish.ImageSharingIntent;
import com.gsma.services.rcs.ish.ImageSharingServiceConfiguration;
import com.orangelabs.rcs.core.Core;
import com.orangelabs.rcs.core.content.ContentManager;
import com.orangelabs.rcs.core.content.MmContent;
import com.orangelabs.rcs.core.ims.service.richcall.image.ImageTransferSession;
import com.orangelabs.rcs.platform.AndroidFactory;
import com.orangelabs.rcs.platform.file.FileDescription;
import com.orangelabs.rcs.platform.file.FileFactory;
import com.orangelabs.rcs.provider.settings.RcsSettings;
import com.orangelabs.rcs.provider.sharing.RichCallHistory;
import com.orangelabs.rcs.service.broadcaster.ImageSharingEventBroadcaster;
import com.orangelabs.rcs.service.broadcaster.JoynServiceRegistrationEventBroadcaster;
import com.orangelabs.rcs.utils.logger.Logger;

/**
 * Image sharing service implementation
 * 
 * @author Jean-Marc AUFFRET
 */
public class ImageSharingServiceImpl extends IImageSharingService.Stub {

	/**
	 * List of image sharing sessions
	 */
	private static Hashtable<String, IImageSharing> ishSessions = new Hashtable<String, IImageSharing>();

	private final ImageSharingEventBroadcaster mImageSharingEventBroadcaster = new ImageSharingEventBroadcaster();

	private final JoynServiceRegistrationEventBroadcaster mJoynServiceRegistrationEventBroadcaster = new JoynServiceRegistrationEventBroadcaster();

	/**
	 * The logger
	 */
	private static final Logger logger = Logger.getLogger(ImageSharingServiceImpl.class.getSimpleName());

	/**
	 * Lock used for synchronization
	 */
	private Object lock = new Object();

	/**
	 * Constructor
	 */
	public ImageSharingServiceImpl() {
		if (logger.isActivated()) {
			logger.info("Image sharing service API is loaded");
		}
	}

	/**
	 * Close API
	 */
	public void close() {
		// Clear list of sessions
		ishSessions.clear();
		
		if (logger.isActivated()) {
			logger.info("Image sharing service API is closed");
		}
	}

	/**
	 * Add an image sharing session in the list
	 * 
	 * @param session Image sharing session
	 */
	protected static void addImageSharingSession(ImageSharingImpl session) {
		if (logger.isActivated()) {
			logger.debug("Add an image sharing session in the list (size=" + ishSessions.size() + ")");
		}
		
		ishSessions.put(session.getSharingId(), session);
	}

	/**
	 * Remove an image sharing session from the list
	 * 
	 * @param sessionId Session ID
	 */
	protected static void removeImageSharingSession(String sessionId) {
		if (logger.isActivated()) {
			logger.debug("Remove an image sharing session from the list (size=" + ishSessions.size() + ")");
		}
		
		ishSessions.remove(sessionId);
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
	 * Registers a listener on service registration events
	 *
	 * @param listener Service registration listener
	 */
	public void addServiceRegistrationListener(IJoynServiceRegistrationListener listener) {
		if (logger.isActivated()) {
			logger.info("Add a service listener");
		}
		synchronized (lock) {
			mJoynServiceRegistrationEventBroadcaster.addServiceRegistrationListener(listener);
		}
	}

	/**
	 * Unregisters a listener on service registration events
	 *
	 * @param listener Service registration listener
	 */
	public void removeServiceRegistrationListener(IJoynServiceRegistrationListener listener) {
		if (logger.isActivated()) {
			logger.info("Remove a service listener");
		}
		synchronized (lock) {
			mJoynServiceRegistrationEventBroadcaster.removeServiceRegistrationListener(listener);
		}
	}

	/**
	 * Receive registration event
	 *
	 * @param state Registration state
	 */
	public void notifyRegistrationEvent(boolean state) {
		// Notify listeners
		synchronized (lock) {
			if (state) {
				mJoynServiceRegistrationEventBroadcaster.broadcastServiceRegistered();
			} else {
				mJoynServiceRegistrationEventBroadcaster.broadcastServiceUnRegistered();
			}
		}
	}

    /**
     * Receive a new image sharing invitation
     * 
     * @param session Image sharing session
     */
    public void receiveImageSharingInvitation(ImageTransferSession session) {
		if (logger.isActivated()) {
			logger.info("Receive image sharing invitation from " + session.getRemoteContact());
		}
		ContactId contact = session.getRemoteContact();
		// Update rich call history
		RichCallHistory.getInstance().addImageSharing(contact, session.getSessionID(),
				ImageSharing.Direction.INCOMING,
				session.getContent(),
				ImageSharing.State.INVITED);

		// Add session in the list
		ImageSharingImpl sessionApi = new ImageSharingImpl(session, mImageSharingEventBroadcaster);
		ImageSharingServiceImpl.addImageSharingSession(sessionApi);
    	
		// Broadcast intent related to the received invitation
    	Intent intent = new Intent(ImageSharingIntent.ACTION_NEW_INVITATION);
    	intent.addFlags(Intent.FLAG_EXCLUDE_STOPPED_PACKAGES);
    	intent.putExtra(ImageSharingIntent.EXTRA_CONTACT, (Parcelable)contact);
    	intent.putExtra(ImageSharingIntent.EXTRA_DISPLAY_NAME, session.getRemoteDisplayName());
    	intent.putExtra(ImageSharingIntent.EXTRA_SHARING_ID, session.getSessionID());
    	intent.putExtra(ImageSharingIntent.EXTRA_FILENAME, session.getContent().getName());
    	intent.putExtra(ImageSharingIntent.EXTRA_FILESIZE, session.getContent().getSize());
    	intent.putExtra(ImageSharingIntent.EXTRA_FILETYPE, session.getContent().getEncoding());
    	AndroidFactory.getApplicationContext().sendBroadcast(intent);
    }

    /**
     * Returns the configuration of image sharing service
     * 
     * @return Configuration
     */
    public ImageSharingServiceConfiguration getConfiguration() {
    	return new ImageSharingServiceConfiguration(
    			RcsSettings.getInstance().getMaxImageSharingSize());
	}    
    
    /**
     * Shares an image with a contact. The parameter file contains the URI
     * of the image to be shared(for a local or a remote image). An exception if thrown if there is
     * no ongoing CS call. The parameter contact supports the following formats: MSISDN
     * in national or international format, SIP address, SIP-URI or Tel-URI. If the format
     * of the contact is not supported an exception is thrown.
     * 
     * @param contact Contact ID
     * @param file Uri of file to share
     * @return Image sharing
     * @throws ServerApiException
     */
    public IImageSharing shareImage(ContactId contact, Uri file) throws ServerApiException {
		if (logger.isActivated()) {
			logger.info("Initiate an image sharing session with " + contact);
		}

		// Test IMS connection
		ServerApiUtils.testIms();

		try {
			// Create an image content
			FileDescription desc = FileFactory.getFactory().getFileDescription(file);
			MmContent content = ContentManager.createMmContent(file, desc.getSize(), desc.getName());

			// Initiate a sharing session
			final ImageTransferSession session = Core.getInstance().getRichcallService().initiateImageSharingSession(contact, content, null);

			// Update rich call history
			RichCallHistory.getInstance().addImageSharing(contact, session.getSessionID(),
					ImageSharing.Direction.OUTGOING,
	    			session.getContent(),
	    			ImageSharing.State.INITIATED);

			// Add session listener
			ImageSharingImpl sessionApi = new ImageSharingImpl(session, mImageSharingEventBroadcaster);

			// Start the session
	        new Thread() {
	    		public void run() {
	    			session.startSession();
	    		}
	    	}.start();	
			
			// Add session in the list
			addImageSharingSession(sessionApi);
			return sessionApi;
		} catch(Exception e) {
			// TODO:Handle Security exception in CR026
			if (logger.isActivated()) {
				logger.error("Unexpected error", e);
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
		if (logger.isActivated()) {
			logger.info("Get image sharing sessions");
		}

		try {
			ArrayList<IBinder> result = new ArrayList<IBinder>(ishSessions.size());
			for (Enumeration<IImageSharing> e = ishSessions.elements() ; e.hasMoreElements() ;) {
				IImageSharing sessionApi = e.nextElement() ;
				result.add(sessionApi.asBinder());
			}
			return result;
		} catch(Exception e) {
			if (logger.isActivated()) {
				logger.error("Unexpected error", e);
			}
			throw new ServerApiException(e.getMessage());
		}
    }    

    /**
     * Returns a current image sharing from its unique ID
     * 
     * @return Image sharing
     * @throws ServerApiException
     */
    public IImageSharing getImageSharing(String sharingId) throws ServerApiException {
		if (logger.isActivated()) {
			logger.info("Get image sharing session " + sharingId);
		}

		return ishSessions.get(sharingId);
    }    

	/**
	 * Adds an event listener on image sharing events
	 * 
	 * @param listener Listener
	 */
	public void addEventListener(IImageSharingListener listener) {
		if (logger.isActivated()) {
			logger.info("Add an Image sharing event listener");
		}
		synchronized (lock) {
			mImageSharingEventBroadcaster.addEventListener(listener);
		}
	}

	/**
	 * Removes an event listener on image sharing events
	 * 
	 * @param listener Listener
	 */
	public void removeEventListener(IImageSharingListener listener) {
		if (logger.isActivated()) {
			logger.info("Remove an Image sharing event listener");
		}
		synchronized (lock) {
			mImageSharingEventBroadcaster.removeEventListener(listener);
		}
	}

	/**
	 * Returns service version
	 * 
	 * @return Version
	 * @see JoynService.Build.VERSION_CODES
	 * @throws ServerApiException
	 */
	public int getServiceVersion() throws ServerApiException {
		return JoynService.Build.API_VERSION;
	}
}
