/**
 *  Nexia Doorbell Sensor - DB100Z for Hubitat
 *  Located at https://github.com/dandiodati/Hubitat/tree/master/Drivers/nexia-doorbell-sensor.src/nexia-doorbell-sensor.groovy
 *  Copyright 2016 DarwinsDen.com
 *
 *  For installation instructions, DB100Z device review information, documentation and images,
 *  or for questions or to provide feedback on this device handler, please visit: 
 *
 *      original file created by darwinsden.com/db100z
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *	Author: dandiodati@gmail.com
 *	Orginal Author: Darwin@DarwinsDen.com
 *
 *	Changelog:
 *
 *	1.0 (1/16/2020) -	Initial 1.0 Release copied over from https://github.com/DarwinsDen/SmartThingsPublic/blob/master/devicetypes/darwinsden/nexia-doorbell-sensor.src/nexia-doorbell-sensor.groovy
 *
 */
 
metadata {
	definition (name: "Nexia Doorbell Sensor", namespace: "darwinsden", author: "Darwin") {
		capability "Actuator"
		capability "Switch"
        capability "Momentary"
        capability "PushableButton"
        capability "Contact Sensor"
		capability "Sensor"
		capability "Battery"
		capability "Refresh"
        capability "Configuration"
		
        attribute "status", "enum", ["off", "doorbell"]
        
		fingerprint deviceId: "0x0701", inClusters: "0x5E, 0x86, 0x72, 0x5A, 0x73, 0x80, 0x70, 0x71, 0x85, 0x59, 0x84, 0x7A"
	}

	simulator {
	}

    preferences {
        input "pushToBreak", "bool", title: "Doorbell circuit is push-to-break instead of push-to-make",  defaultValue: false,  displayDuringSetup: true, required: false	
    }
    
	tiles(scale: 2) {
    	multiAttributeTile(name:"status", type: "generic", width: 6, height: 4){
				tileAttribute ("device.status", key: "PRIMARY_CONTROL") {
					attributeState "off", label: "Off", icon:"st.Home.home30", backgroundColor:"#ffffff"
					attributeState "doorbell", label: "Ringing", icon:"st.alarm.alarm.alarm", backgroundColor:"#53a7c0"
				}
        }

		valueTile("battery", "device.battery", decoration: "flat", inactiveLabel: false, width: 2, height: 2) {
			state "default", label:'${currentValue}% battery', unit:""
		}

		standardTile("refresh", "device.refresh", inactiveLabel: false, decoration: "flat", width: 2, height: 2) {
			state "default", action:"refresh.refresh", icon:"st.secondary.refresh"
		}
        
        valueTile("firmwareVersion", "device.firmwareVersion", width:2, height: 2, decoration: "flat", inactiveLabel: false) {
			state "default", label: '${currentValue}'
		}
  

		main "status"
		details(["status", "battery", "refresh", "firmwareVersion"])
	}
}

def refresh() {
	   sendEvent(name: "status", value: "off", displayed: false, isStateChange: true)
	   sendEvent(name: "switch", value: "off", displayed: false, isStateChange: true)
       updated()
       configure()
 }

def configure() {
   log.debug ("configure() called")
   def commands = []
   commands << zwave.versionV1.versionGet().format()
   commands << zwave.batteryV1.batteryGet().format()
   delayBetween(commands,500)
}

def zwaveEvent(hubitat.zwave.commands.wakeupv2.WakeUpNotification cmd) {
    def event = createEvent(descriptionText: "${device.displayName} woke up", displayed: false)
    def result = []
    
    // Request battery info on wake up if levels have not been received within last 24 hours
	if (!state.lastBatteryReport || (new Date().time) - state.lastBatteryReport > 24*60*60*1000) {
       result += response(zwave.batteryV1.batteryGet().format())
       result += response("delay 1200")
    }
    result += response(zwave.wakeUpV1.wakeUpNoMoreInformation().format()) 
    return result
}

def zwaveEvent(hubitat.zwave.commands.versionv1.VersionReport cmd) {	
    //updateDataValue("applicationVersion", "${cmd.applicationVersion}")
    log.debug ("received Version Report")
    log.debug "applicationVersion:      ${cmd.applicationVersion}"
    log.debug "applicationSubVersion:   ${cmd.applicationSubVersion}"
    state.firmwareVersion=cmd.applicationVersion+'.'+cmd.applicationSubVersion
    log.debug "zWaveLibraryType:        ${cmd.zWaveLibraryType}"
    log.debug "zWaveProtocolVersion:    ${cmd.zWaveProtocolVersion}"
    log.debug "zWaveProtocolSubVersion: ${cmd.zWaveProtocolSubVersion}"
    setFirmwareVersion()
    createEvent([descriptionText: "Firmware V"+state.firmwareVersion, isStateChange: false])
}


// Unexpected command received
def zwaveEvent (hubitat.zwave.Command cmd) {
	   log.debug("Unhandled Command: $cmd")
	   return createEvent(descriptionText: cmd.toString(), isStateChange: false)
}

def switchOffVerification() {
       if (state.bellIsRinging) {
            log.debug("${device.displayName} switchOffVerification setting switch off")
            sendEvent(name: "status", value: "off", descriptionText: "Button released via verification check", isStateChange: true)
       }
       state.bellIsRinging = false
       sendEvent(name: "status", value: "off", displayed: false, isStateChange: true)
	   sendEvent(name: "switch", value: "off", displayed: false, isStateChange: true)
       sendEvent(name: "contact", value: "closed", displayed: false, isStateChange: true)      
}

//Battery Level received
def zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd) {
       def result = null
       state.lastBatteryReport = new Date().time
       def batteryLevel = cmd.batteryLevel
       log.info("Battery: $batteryLevel")
       result = createEvent(name: "battery", value: batteryLevel, unit: "%", descriptionText: "Battery%: $batteryLevel", isStateChange: true)   
       return result
}

//Notification received
def zwaveEvent(hubitat.zwave.commands.notificationv3.NotificationReport cmd) {
       def result = []
    
       // if the bell is not already ringing, record as a button press. We can't always trust an event of 1 to be a 
       // button press if messages arrive out of order or are missed
    
       if (!state.bellIsRinging) { 
           if (!pushToBreak | cmd.event == 0) //Event 1 will never be a doorbell trigger for a push to break circuit
           {
             // bell is not ringing - signal a button press
             state.bellIsRinging = true 
             log.debug("${device.displayName} notification event = $cmd.event triggered switch on")
             runIn(10, switchOffVerification) //set to turn off switch in 10 seconds in case the button release event is missed   
	         result += createEvent(name: "status", value: "doorbell", descriptionText: "Button pressed", isStateChange: true)
	         result += createEvent(name: "switch", value: "on", displayed: false, isStateChange: true)
             result += createEvent(name: "momentary", value: "pushed", displayed: false, isStateChange: true)
             result += createEvent(name: "button", value: "pushed", data: [buttonNumber: "1"], displayed: false, isStateChange: true)
             result += createEvent(name: "contact", value: "open", displayed: false, isStateChange: true)             
           }
       } else {
           // bell is ringing - signal a button release
           state.bellIsRinging = false
           log.debug("${device.displayName} notification event = $cmd.event triggered switch off")
           result += createEvent(name: "status", value: "off", descriptionText: "Button released", isStateChange: true)
	       result += createEvent(name: "switch", value: "off", displayed: false, isStateChange: true)
           result += createEvent(name: "contact", value: "closed", displayed: false, isStateChange: true)             
       } 
       return result
}

def setFirmwareVersion() {
   def versionInfo = ''
   if (state.firmwareVersion)
   {
      versionInfo=versionInfo+"Firmware V"+state.firmwareVersion
   }
   else 
   {
     versionInfo=versionInfo+"Firmware unknown"
   }   
   sendEvent(name: "firmwareVersion",  value: versionInfo, isStateChange: true)
}

def parse(description) {
	  def result = null
      log.debug (description)
	  if (description.startsWith("Err")) {
		   result = createEvent(descriptionText: description, isStateChange: true)
	  } else if (description != "updated") {
          def cmd = zwave.parse(description, [0x71: 3, 0x80: 1])
		  if (cmd) {
		      result = zwaveEvent(cmd)
		  } else {
			  log.debug("Couldn't zwave.parse '$description'")
		  }
	  }
	  return result
}

def updated() 
{
   log.debug ("updating")
   if (pushToBreak)
   {
      state.pushToBreak = true
   }
   else
   {
      state.pushToBreak = false
   } 
}   
