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

package org.gsma.joyn.contacts;

import org.gsma.joyn.capability.Capabilities;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Joyn contact
 * 
 * @author Jean-Marc AUFFRET
 */
public class JoynContact implements Parcelable {
	/**
	 * Capabilities
	 */
	private Capabilities capabilities = null;
	
	/**
	 * Contact ID
	 */
	private String contact = null;
	
	/**
	 * Registration state
	 */
	private boolean registered = false;
	
    /**
	 * Constructor
	 * 
	 * @param contact Contact ID
	 * @param registered Registration state
	 * @param capabilities Capabilities 
	 */
	public JoynContact(String contact, boolean registered, Capabilities capabilities) {
		this.contact = contact;
		this.registered = registered;
		this.capabilities = capabilities;
	}
	
	/**
	 * Constructor
	 * 
	 * @param source Parcelable source
	 */
	public JoynContact(Parcel source) {
		contact = source.readString();
		registered = source.readInt() != 0;
		byte flag = source.readByte();
		if (flag > 0) {
			this.capabilities = Capabilities.CREATOR.createFromParcel(source);
		} else {
			this.capabilities = null;
		}
    }
	
	/**
	 * Describe the kinds of special objects contained in this Parcelable's
	 * marshalled representation
	 * 
	 * @return Integer
	 */
	public int describeContents() {
        return 0;
    }

	/**
	 * Write parcelable object
	 * 
	 * @param dest The Parcel in which the object should be written
	 * @param flags Additional flags about how the object should be written
	 */
    public void writeToParcel(Parcel dest, int flags) {
    	dest.writeString(contact);
    	dest.writeInt(registered ? 1 : 0);
    	dest.writeParcelable(capabilities, flags);
    }

    /**
     * Parcelable creator
     */
    public static final Parcelable.Creator<JoynContact> CREATOR
            = new Parcelable.Creator<JoynContact>() {
        public JoynContact createFromParcel(Parcel source) {
            return new JoynContact(source);
        }

        public JoynContact[] newArray(int size) {
            return new JoynContact[size];
        }
    };	
		
	/**
	 * Returns the canonical contact ID (i.e. MSISDN)
	 * 
	 * @return contact
	 */
	public String getContact(){
		return contact;
	}
	
	/**
	 * Is contact online (i.e. registered to the service platform)
	 * 
	 * @return Boolean
	 */
	public boolean isRegistered(){
		return registered;
	}
	
	/**
	 * Returns the capabilities associated to the contact
	 * 
	 * @return Capabilities
	 */
	public Capabilities getCapabilities(){
		return capabilities;
	}	
}