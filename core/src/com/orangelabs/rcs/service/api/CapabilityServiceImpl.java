/*******************************************************************************
 * Software Name : RCS IMS Stack
 *
 * Copyright (C) 2010 France Telecom S.A.
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
 ******************************************************************************/

package com.orangelabs.rcs.service.api;

import java.util.HashSet;
import java.util.Set;

import com.gsma.services.rcs.IJoynServiceRegistrationListener;
import com.gsma.services.rcs.JoynService;
import com.gsma.services.rcs.capability.Capabilities;
import com.gsma.services.rcs.capability.ICapabilitiesListener;
import com.gsma.services.rcs.capability.ICapabilityService;
import com.gsma.services.rcs.contacts.ContactId;
import com.orangelabs.rcs.core.Core;
import com.orangelabs.rcs.provider.eab.ContactsManager;
import com.orangelabs.rcs.provider.settings.RcsSettings;
import com.orangelabs.rcs.service.broadcaster.CapabilitiesBroadcaster;
import com.orangelabs.rcs.service.broadcaster.JoynServiceRegistrationEventBroadcaster;
import com.orangelabs.rcs.utils.logger.Logger;

/**
 * Capability service API implementation
 * 
 * @author Jean-Marc AUFFRET
 */
public class CapabilityServiceImpl extends ICapabilityService.Stub {

	private final JoynServiceRegistrationEventBroadcaster mJoynServiceRegistrationEventBroadcaster = new JoynServiceRegistrationEventBroadcaster();

	private final CapabilitiesBroadcaster mCapabilitiesBroadcaster = new CapabilitiesBroadcaster();

	/**
	 * Lock used for synchronization
	 */
	private final Object lock = new Object();

    /**
	 * The logger
	 */
	private final Logger logger = Logger.getLogger(getClass().getName());

	/**
	 * Constructor
	 */
	public CapabilityServiceImpl() {
		if (logger.isActivated()) {
			logger.info("Capability service API is loaded");
		}
	}

	/**
	 * Close API
	 */
	public void close() {
		if (logger.isActivated()) {
			logger.info("Capability service API is closed");
		}
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
     * Returns the capabilities supported by the local end user. The supported
     * capabilities are fixed by the MNO and read during the provisioning.
     * 
     * @return Capabilities
     */
	public Capabilities getMyCapabilities() {
		com.orangelabs.rcs.core.ims.service.capability.Capabilities capabilities = RcsSettings.getInstance().getMyCapabilities();
		Set<String> exts = new HashSet<String>(capabilities.getSupportedExtensions());
		return new Capabilities(capabilities.isImageSharingSupported(),
				capabilities.isVideoSharingSupported(),
				capabilities.isImSessionSupported(),
				capabilities.isFileTransferSupported() || capabilities.isFileTransferHttpSupported(),
				capabilities.isGeolocationPushSupported(),
				capabilities.isIPVoiceCallSupported(),
				capabilities.isIPVideoCallSupported(),
    			exts,
    			capabilities.isSipAutomata());
	}

    /**
     * Returns the capabilities of a given contact from the local database. This
     * method doesn�t request any network update to the remote contact. The parameter
     * contact supports the following formats: MSISDN in national or international
     * format, SIP address, SIP-URI or Tel-URI. If the format of the contact is not
     * supported an exception is thrown.
     * 
     * @param contact ContactId
     * @return Capabilities
     */
	public Capabilities getContactCapabilities(ContactId contact) {
		if (logger.isActivated()) {
			logger.info("Get capabilities for contact " + contact);
		}

		// Read capabilities in the local database
		com.orangelabs.rcs.core.ims.service.capability.Capabilities capabilities = ContactsManager.getInstance().getContactCapabilities(contact);
		if (capabilities != null) {
    		Set<String> exts = new HashSet<String>(capabilities.getSupportedExtensions());
			return new Capabilities(
    				capabilities.isImageSharingSupported(),
    				capabilities.isVideoSharingSupported(),
    				capabilities.isImSessionSupported(),
    				capabilities.isFileTransferSupported() || capabilities.isFileTransferHttpSupported(),
    				capabilities.isGeolocationPushSupported(),
    				capabilities.isIPVoiceCallSupported(),
    				capabilities.isIPVideoCallSupported(),
    				exts,
    				capabilities.isSipAutomata()); 
		} else {
			return null;
		}
	}

    /**
	 * Requests capabilities to a remote contact. This method initiates in background
	 * a new capability request to the remote contact by sending a SIP OPTIONS. The
	 * result of the capability request is sent asynchronously via callback method of
	 * the capabilities listener. A capability refresh is only sent if the timestamp
	 * associated to the capability has expired (the expiration value is fixed via MNO
	 * provisioning). The parameter contact supports the following formats: MSISDN in
	 * national or international format, SIP address, SIP-URI or Tel-URI. If the format
	 * of the contact is not supported an exception is thrown. The result of the
	 * capability refresh request is provided to all the clients that have registered
	 * the listener for this event.
	 * 
	 * @param contact ContactId
	 * @throws ServerApiException
	 */
	public void requestContactCapabilities(final ContactId contact) throws ServerApiException {
		if (logger.isActivated()) {
			logger.info("Request capabilities for contact " + contact);
		}

		// Test IMS connection
		ServerApiUtils.testIms();

		// Request contact capabilities
		try {
	        new Thread() {
	    		public void run() {
					Core.getInstance().getCapabilityService().requestContactCapabilities(contact);
	    		}
	    	}.start();
		} catch(Exception e) {
			if (logger.isActivated()) {
				logger.error("Unexpected error", e);
			}
			throw new ServerApiException(e.getMessage());
		}
	}

	/**
     * Receive capabilities from a contact
     * 
     * @param contact ContactId
     * @param capabilities Capabilities
     */
    public void receiveCapabilities(ContactId contact, com.orangelabs.rcs.core.ims.service.capability.Capabilities capabilities) {
    	synchronized(lock) {
    		if (logger.isActivated()) {
    			logger.info("Receive capabilities for " + contact);
    		}
	
    		// Create capabilities instance
    		Set<String> exts = new HashSet<String>(capabilities.getSupportedExtensions());
    		Capabilities c = new Capabilities(
    				capabilities.isImageSharingSupported(),
    				capabilities.isVideoSharingSupported(),
    				capabilities.isImSessionSupported(),
    				capabilities.isFileTransferSupported(),
    				capabilities.isGeolocationPushSupported(),
    				capabilities.isIPVoiceCallSupported(),
    				capabilities.isIPVideoCallSupported(),
    				exts,
    				capabilities.isSipAutomata()); 

			// Notify capabilities listeners
			notifyListeners(contact, c);
    	}
    }

	/**
	 * Notify listeners
	 *
	 * @param contact ContactId
	 * @param capabilities Capabilities
	 */
	private void notifyListeners(ContactId contact, Capabilities capabilities) {
		mCapabilitiesBroadcaster.broadcastCapabilitiesReceived(contact,
				capabilities);
	}

    /**
	 * Requests capabilities for all contacts existing in the local address book. This
	 * method initiates in background new capability requests for each contact of the
	 * address book by sending SIP OPTIONS. The result of a capability request is sent
	 * asynchronously via callback method of the capabilities listener. A capability
	 * refresh is only sent if the timestamp associated to the capability has expired
	 * (the expiration value is fixed via MNO provisioning). The result of the capability
	 * refresh request is provided to all the clients that have registered the listener
	 * for this event.
	 * 
	 * @throws ServerApiException
	 */
	public void requestAllContactsCapabilities() throws ServerApiException {
		if (logger.isActivated()) {
			logger.info("Request all contacts capabilities");
		}

		// Test IMS connection
		ServerApiUtils.testIms();

		// Request all contacts capabilities
		try {
	        Thread t = new Thread() {
	    		public void run() {
	    			Set<ContactId> contactSet = ContactsManager.getInstance().getAllContacts();
	    			Core.getInstance().getCapabilityService().requestContactCapabilities(contactSet);
	    		}
	    	};
	    	t.start();
		} catch(Exception e) {
			if (logger.isActivated()) {
				logger.error("Unexpected error", e);
			}
			throw new ServerApiException(e.getMessage());
		}
	}

	/**
	 * Registers a capabilities listener on any contact
	 *
	 * @param listener Capabilities listener
	 */
	public void addCapabilitiesListener(ICapabilitiesListener listener) {
		if (logger.isActivated()) {
			logger.info("Add a listener");
		}
		synchronized (lock) {
			mCapabilitiesBroadcaster.addCapabilitiesListener(listener);
		}
	}

	/**
	 * Unregisters a capabilities listener
	 *
	 * @param listener Capabilities listener
	 */
	public void removeCapabilitiesListener(ICapabilitiesListener listener) {
		if (logger.isActivated()) {
			logger.info("Remove a listener");
		}
		synchronized (lock) {
			mCapabilitiesBroadcaster.removeCapabilitiesListener(listener);
		}
	}

	/**
	 * Registers a listener for receiving capabilities of a given contact
	 *
	 * @param contact ContactId
	 * @param listener Capabilities listener
	 */
	public void addContactCapabilitiesListener(ContactId contact, ICapabilitiesListener listener) {
		if (logger.isActivated()) {
			logger.info("Add a listener for contact " + contact);
		}
		synchronized (lock) {
			mCapabilitiesBroadcaster.addContactCapabilitiesListener(contact, listener);
		}
	}

	/**
	 * Unregisters a listener of capabilities for a given contact
	 *
	 * @param contact ContactId
	 * @param listener Capabilities listener
	 */
	public void removeContactCapabilitiesListener(ContactId contact, ICapabilitiesListener listener) {
		if (logger.isActivated()) {
			logger.info("Remove a listener for contact " + contact);
		}
		synchronized (lock) {
			mCapabilitiesBroadcaster.removeContactCapabilitiesListener(contact, listener);
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