import groovy.transform.Field

@Field String VERSION = "1.0.0"

@Field List<String> LOG_LEVELS = ["error", "warn", "info", "debug", "trace"]
@Field String DEFAULT_LOG_LEVEL = LOG_LEVELS[2]

metadata {
  definition (name: "Sonoff RF Bridge over MQTT - Shade Child Device over MQTT", namespace: "snargit", author: "David BAILEY", importUrl: "https://raw.githubusercontent.com/snargit/Hubitat/main/Sonoff_RF_Bridge_MQTT_Shade_Child.groovy") {
    capability "Actuator"
    capability "WindowShade"
    command "stop"
    attribute "switch", "string"
  }
  preferences {
    section { // General
      input name: "logLevel", title: "Log Level", type: "enum", options: LOG_LEVELS, defaultValue: DEFAULT_LOG_LEVEL, required: false
    }
  }
}

def installed() {
  logger("debug", "installed(${VERSION})")

  if (state.driverInfo == null || state.driverInfo.isEmpty() || state.driverInfo.ver != VERSION) {
    state.driverInfo = [ver:VERSION, status:'Current version']
  }

  if (state.deviceInfo == null) {
    state.deviceInfo = [:]
  }

  initialize()
}

def uninstalled() {
  logger("debug", "uninstalled()")
  unschedule()
}

def updated() {
  logger("debug", "updated()")

  if (!state.driverInfo?.ver || state.driverInfo.isEmpty() || state.driverInfo.ver != VERSION) {
    installed()
  }
  unschedule()
  initialize()
}

def initialize() {
  logger("debug", "initialize()")

  schedule("0 0 12 */7 * ?", updateCheck)
}

def parse(value) {
  logger("debug", "parse() - value: ${value?.inspect()}")
  if (value) {
    sendEvent(value)
  }
}

def close() {
  logger("debug", "close()")
  sendEvent([name: "windowShade", value: "closing", displayed: true])
  parent.childClose(device.deviceNetworkId)
}
def off() {
  logger("debug", "off()")
  close()
}

def open() {
  logger("debug", "open()")
  sendEvent(name: "windowShade", value: "opening", displayed: true)
  parent.childOpen(device.deviceNetworkId)
}
def on() {
  logger("debug", "on()")
  open()
}

def setPosition(BigDecimal value) {
  logger("debug", "setPosition(${value})")
  sendEvent(name: "windowShade", value: "partially open", displayed: true)
  parent.childPosition(device.deviceNetworkId, value)
}
def setLevel(BigDecimal value) {
  logger("debug", "setLevel(${value})")
  setPosition(value)
}

def startPositionChange(direction) {
  logger("debug", "startPositionChange(${direction})")
  parent.childStartPositionChange(device.deviceNetworkId, direction)
}

def stopPostionChange() {
  logger("debug", "stopPositionChange()")
  parent.childStopPositionChange(device.deviceNetworkId)
}

def stop() {
  logger("debug", "stop()")
  sendEvent(name: "windowShade", value: "partially open", displayed: true)
  parent.childStop(device.deviceNetworkId)
}

/**
 * @param level Level to log at, see LOG_LEVELS for options
 * @param msg Message to log
 */
private logger(level, msg) {
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
  Map params = [uri: "https://raw.githubusercontent.com/snargit/Hubitat/main/Sonoff_RF_Bridge_MQTT_Shade_Child.groovy"]
  asynchttpGet("updateCheckHandler", params)
}

private updateCheckHandler(resp, data) {
  if (resp?.getStatus() == 200) {
    Integer ver_online = (resp?.getData() =~ /(?m).*String VERSION = "(\S*)".*/).with { hasGroup() ? it[0][1]?.replaceAll('[vV]', '')?.replaceAll('\\.', '').toInteger() : null }
    if (ver_online == null) { logger("error", "updateCheck() - Unable to extract version from source file") }

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