/**
 * Copyright 2019 vip.com.
 * <p>
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
 * </p>
 */

package com.vip.pallas.exception;

import com.vip.pallas.entity.BusinessLevelExceptionCode;

public class BusinessLevelException extends RuntimeException {

	/**
	 * 
	 */
	private static final long serialVersionUID = 8524391367741693652L;

	private int errorCode;
	private String message;

	public int getErrorCode() {
		return errorCode;
	}

	public void setErrorCode(int errorCode) {
		this.errorCode = errorCode;
	}

	@Override
	public String getMessage() {
		return message;
	}

	public void setMessage(String message) {
		this.message = message;
	}

	public BusinessLevelException() {

	}

	/**
	 * use {@link BusinessLevelException#BusinessLevelException(BusinessLevelExceptionCode, String)} instead
	 */
	@Deprecated
	public BusinessLevelException(int errorCode, String message){
		this.errorCode = errorCode;
		this.message = message;
	}

	public BusinessLevelException(BusinessLevelExceptionCode code,String message){

		this.errorCode = code.val();
		this.message=message;
	}
	
	public BusinessLevelException(String msg) {
		super(msg);
	}
}