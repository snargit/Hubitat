import groovy.json.JsonSlurper
import groovy.transform.Field

@Field String VERSION = "1.0.0"

@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[2]

/*
Example VD_JSON definition json string
Note: the string must be defined as one single line (https://www.webtoolkitonline.com/json-minifier.html), see the below examples:
{
  "Shade:Bedroom":{
    "close":"< B0 String that closes the Shade >",
    "open":"< B0 String that opens the Shade >",
    "stop":"< B0 String to stop the Shade >"
  },
  "Switch:Radio":{
    "on":"< B0 String turn on the Switch >",
    "off":"< B0 String turn off the Switch >"
  }
}
*/
@Field String VD_JSON = '{"Shade:Bedroom":{"close":"< B0 String that closes the Shade >","open":"< B0 String that opens the Shade >","stop":"< B0 String to stop the Shade >"},"Switch:Radio":{"on":"< B0 String turn on the Switch >","off":"< B0 String turn off the Switch >"}}'

metadata {
  definition (name: "Sonoff RF Bridge over MQTT", namespace: "snargit", author: "David BAILEY", importUrl: "https://raw.githubusercontent.com/snargit/Hubitat/main/Sonoff_RF_Bridge_MQTT.groovy") {
    capability "Actuator"
    capability "Refresh"
    capability "Initialize"
    capability "Configuration"

    command "clearState"
    command "cleanChild"
    attribute "status", "string"
  }

  preferences {
    section { // General
      input name: "logLevel", title: "Log Level", type: "enum", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: false
      input name: "logDescText", title: "Log Description Text", type: "bool", defaultValue: false, required: false
      input name: "stateCheckInterval", title: "State Check", description: "Check interval of the current state", type: "enum", options:[[0:"Disabled"], [5:"5min"], [10:"10min"], [15:"15min"], [30:"30min"], [2:"1h"], [3:"3h"], [4:"4h"], [6:"6h"], [8:"8h"], [12: "12h"]], defaultValue: 5, required: true
    }
    section { // Configuration
        input name: "MQTTBroker", type: "text", title: "MQTT Broker Address:", required: true, displayDuringSetup: true
        input name: "username", type: "text", title: "MQTT Username:", description: "(blank if none)", required: false, displayDuringSetup: true
        input name: "password", type: "password", title: "MQTT Password:", description: "(blank if none)", required: false, displayDuringSetup: true
        input name: "tasmotaDeviceName", type: "text", title: "Tasmota Device Topic:", description: "The topic set in the MQTT Tasmota configuration", required: true, displayDuringSetup: true
    }
  }
}

def installed()
{
    logger("debug", "installed(${VERSION})")

    if (state.driverInfo == null || state.driverInfo.isEmpty() || state.driverInfo.ver != VERSION) {
        state.driverInfo = [ver:VERSION, status:'Current version']
    }

    if (state.deviceInfo == null) {
        state.deviceInfo = [:]
    }

    state.devicePings = 0
    state.mqttConnected = false;
}

def uninstalled()
{
    mqttDisconnect()
}

def updated()
{
    logger("debug", "updated()")

    if (!state.driverInfo?.ver || state.driverInfo.isEmpty() || state.driverInfo.ver != VERSION) {
        installed()
    }

    if (!state.deviceInfo) {
        refresh()
    }

    unschedule()
    mqttDisconnect()
    configure()
}

def initialize()
{
    logger("debug", "initialize()")
    sendEvent(name: "status", value: "unknown", descriptionText: "Is unknown", displayed: true)

    def slurper = new JsonSlurper()
    def vd_data = slurper.parseText(VD_JSON)

    // Create virtual devices
    vd_data?.each {
        logger("info", "configure() - Creating Virtual Device: ${it.key?.split(':')?.getAt(1)} (${it.key?.split(':')?.getAt(0)})")
        def vd = findOrCreateChild(it.key?.split(':')?.getAt(0), it.key?.split(':')?.getAt(1))
    }
}

def refresh()
{
    logger("debug", "refresh() - state: ${state.inspect()}")
    getDeviceInfo()
}

def configure() {
    logger("debug", "configure()")

    state.devicePings = 0

    mqttConnect()

    schedule("0 0 12 */7 * ?", updateCheck)

    if (stateCheckInterval.toInteger()) {
        if (['5', '10', '15', '30'].contains(stateCheckInterval) ) {
            schedule("0 */${stateCheckInterval} * ? * *", checkState)
        } else {
            schedule("0 0 */${stateCheckInterval} ? * *", checkState)
        }
    }
}

def cleanChild()
{
    logger("debug", "cleanChild() - childDevices: ${childDevices?.size()}")

    childDevices?.each{ deleteChildDevice(it.deviceNetworkId) }
}

def clearState()
{
    logger("debug", "ClearStates() - Clearing device states")

    state.clear()

    if (state?.driverInfo == null) {
        state.driverInfo = [:]
    } else {
        state.driverInfo.clear()
    }

    if (state?.deviceInfo == null) {
        state.deviceInfo = [:]
    } else {
        state.deviceInfo.clear()
    }

    state.devicePings = 0
    state.mqttConnected = false
}

def checkState()
{
    logger("debug", "checkState()")

    if (state?.devicePings >= 4) {
        if (device.currentValue('status') != 'offline') {
            sendEvent([ name: "status", value: 'offline', descriptionText: "Is offline", displayed: true])
        }
        logger("warn", "Device is offline")
    }

    state.devicePings = state.devicePings + 1

    mqttPublish(mqttGetCommandTopic("Status"), 11)
}

def mqttClientStatus(String message)
{
    logger("trace", "mqttClientStatus() - message: ${message?.inspect()}")

    // Connection succeeded - subscribe to topics
    if (message == "Status: Connection succeeded") {
        state.mqttConnected = true
        mqttSubscribe()
    } else {
        status = message.take(6)
        if (status == "Error:") {
            mqttDisconnect()
            state.mqttConnected = false
            runIn(5, "mqttConnect")
        } else {
            logger("info", "mqttClientStatus() - status message ${message}")
        }
    }
}

def parse(String description)
{
    def result = []
    def parsedData = interfaces.mqtt.parseMessage(description)
    if (!parsedData?.isEmpty()) {
        logger("trace", "parse() - topic: ${parsedData.topic}")
        logger("trace", "parse() - payload: ${parsedData.payload}")
        def topic = new String(parsedData.topic)
        topic = topic.substring(topic.lastIndexOf("/")+1)
        def payload = new String(parsedData.payload)
        switch (topic)
        {
            case "RESULT":
                mqttRESULT(payload)
                break
            case "STATUS2":
                mqttSTATUS2(payload)
                break
            case "STATUS11":
                result << mqttSTATUS11(payload)
                break
            default:
                logger("info", "Unimplemented topic ${topic}")
        }
    }
    return result
}

def deviceOnline()
{
  // Sets the device status to online, but only if previously was offline
  Map deviceState = [ name: "status",
                      value: 'online',
                      descriptionText: "Is online",
                      displayed: true
                    ]

  state.devicePings = 0
  logger("info", "Device is online")

  return createEvent(deviceState)
}

def getDeviceInfo()
{
    logger("debug", "getDeviceInfo()")
    mqttPublish(mqttGetCommandTopic("Status"), 0)
}

private def mqttConnect()
{
  try {
      interfaces.mqtt.connect(MQTTBroker, "HubitatSonoffRFBridge", settings?.username, settings?.password)
  } catch (e) {
      logger("error", "mqttConnect error ${e.message}")
  }
}

private def mqttSubscribe()
{
  try {
      if (interfaces.mqtt.isConnected()) {
          interfaces.mqtt.subscribe("tele/${tasmotaDeviceName}/#")
          interfaces.mqtt.subscribe("stat/${tasmotaDeviceName}/#")
      }
  } catch (e) {
      logger("error", "mqttSubscribe error ${e.message}")
  }
}

private def mqttDisconnect()
{
    try {
        if (interfaces.mqtt.isConnected()) {
            interfaces.mqtt.unsubscribe("tele/${tasmotaDeviceName}")
            interfaces.mqtt.unsubscribe("stat/${tasmotaDeviceName}")
            interfaces.mqtt.disconnect()
        }
    } catch (e) {
        logger("error", "mqttDisconnect error ${e.message}")
    }
}

private def mqttRESULT(String value)
{
    def slurper = new JsonSlurper()
    def parsedData = slurper.parseText(value)
}

private def mqttSTATUS2(String value)
{
    def result = []
    def slurper = new JsonSlurper()
    def parsedData = slurper.parseText(value)
    state.deviceInfo = parsedData?.StatusFWR
    return result
}

private def mqttSTATUS11(String value)
{
    def result = []
    def slurper = new JsonSlurper()
    def parsedData = slurper.parseText(value)
    if (parsedData?.StatusSTS?.UptimeSec > 0) {
        result << deviceOnline()
    } else {
        if (device.currentValue('status') != 'offline') {
            result << createEvent([ name: "status", value: 'offline', descriptionText: "Is offline", displayed: true])
        }
    }
    return result
}

// Capability: Shade
private def childClose(String value)
{
    logger("debug", "childClose(${value})")

    try {
        def slurper = new JsonSlurper()
        def vd_data = slurper.parseText(VD_JSON)

        def cd = getChildDevice(value)
        if (cd) {
            (vd_parent, vd_type, vd_name) = value?.split('-', 3)
            if (vd_data?.containsKey(vd_type +':'+ vd_name)) {
                String cv = cd.currentValue("windowShade")
                String rf_cmd = vd_data[vd_type +':'+ vd_name]?.close
                mqttPublish(mqttGetCommandTopic("Backlog"), "RfRaw ${rf_cmd}; RfRaw 0")
                logger("debug", "childClose(${value}) - Shade: ${cv} -> closed")
                cd.parse([[name:"windowShade", value:"closed", descriptionText:"Was closed"]])
                cd.parse([[name:"switch", value:"off", descriptionText:"Was opened"]])
                if(logDescText) {
                    log.info "${cd.displayName} Was closed"
                } else {
                    logger("info", "${cd.displayName} Was closed")
                }
            } else {
                logger("warn", "childClose(${value}) - Could not find the Virtual Device definition")
            }
        } else {
            logger("warn", "childClose(${value}) - Could not find the Virtual Device")
            configure()
        }
    } catch (e) {
        logger("error", "childClose(${value}) - ${e.inspect()}")
    }
}

// Capability: Shade
private def childOpen(String value)
{
    logger("debug", "childOpen(${value})")

    try {
        def slurper = new JsonSlurper()
        def vd_data = slurper.parseText(VD_JSON)

        def cd = getChildDevice(value)
        if (cd) {
            (vd_parent, vd_type, vd_name) = value?.split('-', 3)
            if (vd_data?.containsKey(vd_type +':'+ vd_name)) {
                String cv = cd.currentValue("windowShade")
                String rf_cmd = vd_data[vd_type +':'+ vd_name]?.open
                mqttPublish(mqttGetCommandTopic("Backlog"), "RfRaw ${rf_cmd}; RfRaw 0")
                logger("debug", "childOpen(${value}) - Shade: ${cv} -> open")
                cd.parse([[name:"windowShade", value:"open", descriptionText:"Was opened"]])
                cd.parse([[name:"switch", value:"on", descriptionText:"Was opened"]])
                if(logDescText) {
                    log.info "${cd.displayName} Was opened"
                } else {
                    logger("info", "${cd.displayName} Was opened")
                }
            } else {
                logger("warn", "childOpen(${value}) - Could not find the Virtual Device definition")
            }
        } else {
            logger("warn", "childOpen(${value}) - Could not find the Virtual Device")
            configure()
        }
    } catch (e) {
        logger("error", "childOpen(${value}) - ${e.inspect()}")
    }
}

// Capability: Shade
private def childStop(String value) {
    logger("debug", "childStop(${value})")

    try {
        def slurper = new JsonSlurper()
        def vd_data = slurper.parseText(VD_JSON)

        def cd = getChildDevice(value)
        if (cd) {
            (vd_parent, vd_type, vd_name) = value?.split('-', 3)
            if (vd_data?.containsKey(vd_type +':'+ vd_name)) {
                String cv = cd.currentValue("windowShade")
                String rf_cmd = vd_data[vd_type +':'+ vd_name]?.stop
                mqttPublish(mqttGetCommandTopic("Backlog"), "RfRaw ${rf_cmd}; RfRaw 0")
                logger("debug", "childStop(${value}) - Shade: ${cv} -> partially open")
                cd.parse([[name:"windowShade", value:"partially open", descriptionText:"Was stopped"]])
                if(logDescText) {
                    log.info "${cd.displayName} Was stopped"
                } else {
                    logger("info", "${cd.displayName} Was stopped")
                }
            } else {
                logger("warn", "childStop(${value}) - Could not find the Virtual Device definition")
            }
        } else {
            logger("warn", "childStop(${value}) - Could not find the Virtual Device")
            configure()
        }
    } catch (e) {
        logger("error", "childStop(${value}) - ${e.inspect()}")
    }
}

// Capability: Shade
private def childPosition(String value, BigDecimal position)
{
    logger("debug", "childPosition(${value},${position})")
}

// Capability: Switch
private def childOn(String value) {
    logger("debug", "childOn(${value})")

    try {
        def slurper = new JsonSlurper()
        def vd_data = slurper.parseText(VD_JSON)

        def cd = getChildDevice(value)
        if (cd) {
            (vd_parent, vd_type, vd_name) = value?.split('-', 3)
            if (vd_data?.containsKey(vd_type +':'+ vd_name)) {
                String cv = cd.currentValue("switch")
                String rf_cmd = vd_data[vd_type +':'+ vd_name]?.off
                mqttPublish(mqttGetCommandTopic("Backlog"), "RfRaw ${rf_cmd}; RfRaw 0")
                logger("debug", "childOn(${value}) - switch: ${cv} -> off")
                cd.parse([[name:"switch", value:"off", descriptionText:"Was turned off"]])
                if(logDescText) {
                    log.info "${cd.displayName} Was turned off"
                } else {
                    logger("info", "${cd.displayName} Was turned off")
                }
            } else {
                logger("warn", "childOn(${value}) - Could not find the Virtual Device definition")
            }
        } else {
            logger("warn", "childOn(${value}) - Could not find the Virtual Device")
            configure()
        }
    } catch (e) {
        logger("error", "childOn(${value}) - ${e.inspect()}")
    }
}

// Capability: Switch
private def childOff(String value) {
    logger("debug", "childOff(${value})")

    try {
        def slurper = new JsonSlurper()
        def vd_data = slurper.parseText(VD_JSON)

        def cd = getChildDevice(value)
        if (cd) {
            (vd_parent, vd_type, vd_name) = value?.split('-', 3)
            if (vd_data?.containsKey(vd_type +':'+ vd_name)) {
                String cv = cd.currentValue("switch")
                String rf_cmd = vd_data[vd_type +':'+ vd_name]?.on
                mqttPublish(mqttGetCommandTopic("Backlog"), "RfRaw ${rf_cmd}; RfRaw 0")
                logger("debug", "childOff(${value}) - switch: ${cv} -> on")
                cd.parse([[name:"switch", value:"on", descriptionText:"Was turned on"]])
                if(logDescText) {
                    log.info "${cd.displayName} Was turned on"
                } else {
                    logger("info", "${cd.displayName} Was turned on")
                }
            } else {
                logger("warn", "childOff(${value}) - Could not find the Virtual Device definition")
            }
        } else {
            logger("warn", "childOff(${value}) - Could not find the Virtual Device")
            configure()
        }
    } catch (e) {
        logger("error", "childOff(${value}) - ${e.inspect()}")
    }
}

// Finds / Creates the child device
private def findOrCreateChild(String type, String name) {
  logger("debug", "findOrCreateChild(${type},${name})")
  try {
    String thisId = device.id
    def cd = getChildDevice("${thisId}-${type}-${name}")
    if (!cd) {
      switch (type) {
        case "Shade":
          cd = addChildDevice("Sonoff RF Bridge over MQTT - ${type} Child Device over MQTT", "${thisId}-${type}-${name}", [name: "${type} ${name}", label: "${type} ${name}", isComponent: true])
          cd.parse([[name:"windowShade", value:"closed"]])
        break
        case "Switch":
          cd = addChildDevice("Sonoff RF Bridge over MQTT - ${type} Child Device over MQTT", "${thisId}-${type}-${name}", [name: "${type} ${name}", label: "${type} ${name}", isComponent: true])
          cd.parse([[name:"switch", value:"off"]])
        break
        default :
          logger("error", "findOrCreateChild(${type},${name}) - Device type not found")
        break
      }
    }
    return cd
  } catch (e) {
    logger("error", "findOrCreateChild(${type},${name}) - e: ${e}")
  }
}

private mqttPublish(topic, value)
{
    interfaces.mqtt.publish(topic, value)
}

private mqttGetCommandTopic(command)
{
    String cmd = "cmnd/+${tasmotaDeviceName}/${command}"
    return cmd
}

/**
 * @param level Level to log at, see LOG_LEVELS for options
 * @param msg Message to log
 */
private logger(level, msg)
{
    if (level && msg) {
        Integer levelIdx = LOG_LEVELS.indexOf(level)
        Integer setLevelIdx = LOG_LEVELS.indexOf(logLevel)
        if (setLevelIdx < 0) {
            setLevelIdx = LOG_LEVELS.indexOf(DEFAULT_LOG_LEVEL)
        }
        if (levelIdx <= setLevelIdx) {
            log."${level}" "${device.displayName} ${msg}"
        }
    }
}

def updateCheck() {
    Map params = [uri: "https://raw.githubusercontent.com/snargit/Hubitat/main/Sonoff_RF_Bridge_MQTT.groovy"]
    asynchttpGet("updateCheckHandler", params)
}

private updateCheckHandler(resp, data) {
    if (resp?.getStatus() == 200) {
        Integer ver_online = (resp?.getData() =~ /(?m).*String VERSION = "(\S*)".*/).with { hasGroup() ? it[0][1]?.replaceAll('[vV]', '')?.replaceAll('\\.', '').toInteger() : null }
        if (ver_online == null) {
            logger("error", "updateCheck() - Unable to extract version from source file")
        }

        Integer ver_cur = state.driverInfo?.ver?.replaceAll('[vV]', '')?.replaceAll('\\.', '').toInteger()

        if (ver_online > ver_cur) {
            logger("info", "New version(${ver_online})")
            state.driverInfo.status = "New version (${ver_online})"
        } else if (ver_online == ver_cur) {
            logger("info", "Current version")
            state.driverInfo.status = 'Current version'
        }
    } else {
        logger("error", "updateCheck() - Unable to download source file")
    }
}