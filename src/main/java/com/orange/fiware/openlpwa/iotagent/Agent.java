/**
 * Copyright (C) 2016 Orange
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *          http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * * Created by Christophe AZEMAR on 28/06/2016.
 */

package com.orange.fiware.openlpwa.iotagent;

import com.orange.fiware.openlpwa.domain.DeviceEntity;
import com.orange.fiware.openlpwa.provider.OpenLpwaMqttProviderCallback;
import com.orange.fiware.openlpwa.provider.OpenLpwaProvider;
import com.orange.fiware.openlpwa.repository.DeviceEntityRepository;
import com.orange.fiware.openlpwa.exception.AgentException;
import com.orange.fiware.openlpwa.exception.ConfigurationException;
import com.orange.fiware.openlpwa.provider.OpenLpwaMqttProvider;
import com.orange.fiware.openlpwa.provider.model.DeviceCommand;
import com.orange.fiware.openlpwa.provider.model.DeviceIncomingMessage;
import com.orange.fiware.openlpwa.provider.model.DeviceInfo;
import com.orange.fiware.openlpwa.provider.model.RegisterDeviceCommandParameter;
import com.orange.fiware.openlpwa.ngsi.NgsiManager;
import com.orange.ngsi.model.ContextAttribute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.util.concurrent.SuccessCallback;

import java.util.Date;
import java.util.List;
import java.util.function.BiConsumer;

import static com.orange.ngsi.model.CodeEnum.CODE_200;

@EnableScheduling
@Service
public class Agent {

    private static final Logger logger = LoggerFactory.getLogger(Agent.class);
    @Autowired
    private OpenLpwaProvider openLpwaProvider;
    @Autowired
    private OpenLpwaMqttProvider openLpwaMqttProvider;
    @Autowired
    private DeviceEntityRepository deviceRepository;
    @Autowired
    private NgsiManager ngsiManager;
    @Autowired
    private AgentMqttProviderCallback mqttClientCallback;
    private OpenLpwaNgsiConverter converter;
    private AgentConnectionLostCallback connectionLostCallback;
    private boolean reconnecting = false;

    public void setConnectionLostCallback(AgentConnectionLostCallback connectionLostCallback) {
        this.connectionLostCallback = connectionLostCallback;
    }

    /**
     * Start the IoT agent
     * @param successCallback   Callback called when the agent is correctly started
     * @param failureCallback   Callback called when an error occurs
     */
    public void start(OpenLpwaNgsiConverter converter, AgentSuccessCallback successCallback, AgentFailureCallback failureCallback) {
        logger.debug("Starting the agent");
        if (converter != null) {
            this.converter = converter;
        } else {
            launchFailureCallback(failureCallback, new AgentException("Custom OpenLpwaNgsiConverter is missing."));
            return;
        }

        // Initialization
        openLpwaMqttProvider.setClientCallback(mqttClientCallback);

        // Connect to the Mqtt broker
        logger.debug("Connecting to the Mqtt broker");
        openLpwaMqttProvider.connect(
                connectedClientId -> {
                    logger.debug("Connected to the Mqtt broker");
                    // Subscribe to the Mqtt topic
                    logger.debug("Subscribing to the Mqtt topic");
                    openLpwaMqttProvider.subscribe(
                            subscribedClientId -> {
                                logger.debug("Subscribed to the Mqtt topic");
                                launchSuccessCallback(successCallback);
                            },
                            ex -> {
                                openLpwaMqttProvider.disconnect(null, null);
                                String errorMsg = "Unable to subscribe to Mqtt topic";
                                logger.error(errorMsg, ex);
                                launchFailureCallback(failureCallback, new AgentException(errorMsg, ex));
                            });
                },
                ex -> {
                    String errorMsg = "Unable to connect to the Mqtt broker";
                    logger.error(errorMsg, ex);
                    launchFailureCallback(failureCallback, new AgentException(errorMsg, ex));
                });
    }

    /**
     * Stop the IoT agent
     * @param successCallback   Callback called when the agent is correctly stopped
     * @param failureCallback   Callback called when an error occurs
     */
    public void stop(AgentSuccessCallback successCallback, AgentFailureCallback failureCallback) {
        // Disconnect from the Mqtt broker
        openLpwaMqttProvider.disconnect(
                disconnectedClientId -> {
                    logger.debug("Disconnected from the Mqtt broker");
                    launchSuccessCallback(successCallback);
                },
                ex -> {
                    String errorMsg = "Unable to disconnect from the Mqtt broker";
                    logger.error(errorMsg, ex);
                    launchFailureCallback(failureCallback, new AgentException(errorMsg, ex));
                }
        );
    }

    /**
     * Register a device into the IoT agent
     * @param device            Device to register
     * @param successCallback   Callback called when the device is correctly registered
     * @param failureCallback   Callback called when an error occurs
     */
    public void register(Device device, AgentSuccessCallback successCallback, AgentFailureCallback failureCallback) {
        try {
            checkDevice(device);

            // First check if the device is registered and activated in OpenLpwa provider
            checkOpenLpwaProviderRegistration(device,
                    () -> {
                        try {
                            subscribeToCommands(
                                    device,
                                    subscriptionId -> {
                                        // We can save device without subscriptionId if it's only a sensor
                                        logger.debug("Device correctly registered (EUI: {})", device.getDeviceEUI());
                                        deviceRepository.save(new DeviceEntity(device, subscriptionId));
                                        logger.debug("Entity (EUI:{}) save in Mongo database.", device.getDeviceEUI());
                                        launchSuccessCallback(successCallback);
                                    },
                                    ex -> launchFailureCallback(failureCallback, new AgentException(ex.getMessage(), ex))
                            );
                        } catch (AgentException e) {
                            launchFailureCallback(failureCallback, e);
                        }
                    },
                    ex -> {
                        String errorMsg = String.format("Error while checking OpenLpwa provider registration for device (%s)", device);
                        logger.error(errorMsg, ex);
                        launchFailureCallback(failureCallback, new AgentException(errorMsg, ex));
                    }
            );
        } catch (ConfigurationException ex) {
            String errorMsg = device != null ? String.format("Unable to register the device (%s)", device.getDeviceEUI()) : "Device to register is null";
            logger.error(errorMsg, ex);
            launchFailureCallback(failureCallback, new AgentException(errorMsg, ex));
        }
    }

    /**
     * Unregister a device from the IoT agent
     * @param deviceEUI         Device EUI to unregister
     * @param successCallback   Callback called when the device is correctly unregistered
     * @param failureCallback   Callback called when an error occurs
     */
    public void unregister(String deviceEUI, AgentSuccessCallback successCallback, AgentFailureCallback failureCallback) {
        DeviceEntity registeredDevice = deviceRepository.findOne(deviceEUI);
        if (registeredDevice != null) {
            try {
                unsubscribeToCommands(
                        registeredDevice,
                        () -> {
                            logger.debug("Device (EUI:{}) deleted.", registeredDevice.getDeviceEUI());
                            deviceRepository.delete(registeredDevice);
                            launchSuccessCallback(successCallback);
                        },
                        ex -> launchFailureCallback(failureCallback, new AgentException(ex.getMessage(), ex))
                );
            } catch (AgentException e) {
                launchFailureCallback(failureCallback, e);
            }
        } else {
            launchFailureCallback(failureCallback, new AgentException("Unable to unregister an unknown device."));
        }
    }

    /**
     * Check if a device is valid to use the agent
     * @param device device to validate
     */
    private void checkOpenLpwaProviderRegistration(Device device, AgentSuccessCallback successCallback, AgentFailureCallback failureCallback) throws ConfigurationException {
        logger.debug("Checking device (EUI: {}) registration in OpenLpwa provider", device.getDeviceEUI());
        openLpwaProvider.getDeviceInformation(device.getDeviceEUI()).addCallback(
                result -> {
                    logger.debug("Device (EUI: {}) correctly registered in OpenLpwa provider", device.getDeviceEUI());
                    // Returns an error if the device is not activated in OpenLpwa provider
                    Boolean deviceFound = result.getDeviceStatus() == DeviceInfo.DeviceStatus.ACTIVATED;
                    if (deviceFound) {
                        launchSuccessCallback(successCallback);
                    } else {
                        launchFailureCallback(failureCallback, new AgentException("The device is not activated"));
                    }
                },
                ex -> {
                    String errorMsg = String.format("Unable to check if the device (%s) is registered in OpenLpwa provider", device.getDeviceEUI());
                    logger.error(errorMsg, ex);
                    launchFailureCallback(failureCallback, new AgentException(errorMsg, ex));
                }
        );
    }

    /**
     * Subscribe to device's commands
     * @param device device
     * @param successCallback   Callback called when the device is correctly unregistered
     * @param failureCallback   Callback called when an error occurs
     * @throws AgentException   throws if an error occurs
     */
    private void subscribeToCommands(Device device, SuccessCallback<String> successCallback, AgentFailureCallback failureCallback) throws AgentException {
        // If the device doesn't contain a command, the subscription is not necessary
        if (device.getCommands() != null && device.getCommands().size() > 0) {
            ngsiManager.subscribeToCommands(device).addCallback(
                    result -> {
                        if (result != null && result.getSubscribeError() == null && result.getSubscribeResponse() != null) {
                            logger.debug("Device (EUI:{}) correctly subscribed in the NGSI Context Broker ", device.getDeviceEUI());
                            if (successCallback != null) {
                                successCallback.onSuccess(result.getSubscribeResponse().getSubscriptionId());
                            }
                        } else {
                            logger.error("Unable to subscribe for device (EUI: {})", device.getDeviceEUI());
                            launchFailureCallback(failureCallback, new AgentException("Unable to subscribe in the NGSI Context Broker."));
                        }
                    },
                    ex -> {
                        String errorMsg = String.format("Unable to subscribe device in the NGSI Context Broker (%s)", device.getDeviceEUI());
                        logger.error(errorMsg, ex);
                        launchFailureCallback(failureCallback, new AgentException(errorMsg, ex));
                    }
            );
        } else {
            if (successCallback != null) {
                successCallback.onSuccess(null);
            }
        }
    }

    /**
     * Unsubcribe to device's commands
     * @param device device
     * @param successCallback   Callback called when the device is correctly unregistered
     * @param failureCallback   Callback called when an error occurs
     * @throws AgentException   throws if an error occurs
     */
    private void unsubscribeToCommands(DeviceEntity device, AgentSuccessCallback successCallback, AgentFailureCallback failureCallback) throws AgentException {
        // If the device doesn't contain a command, the unsubscription is not necessary
        if (device.getSubscriptionId() != null) {
            ngsiManager.unsubscribe(device.getSubscriptionId()).addCallback(
                    result -> {
                        if (result != null && result.getStatusCode().getCode().equals(CODE_200.getLabel())) {
                            logger.debug("DeviceEUI:{} correctly unsubscribe", device);
                            launchSuccessCallback(successCallback);
                        } else {
                            launchFailureCallback(failureCallback, new AgentException("Unable to unsubscribe in the NGSI Context Broker."));
                        }
                    },
                    ex -> {
                        String errorMsg = String.format("Unable to unsubscribe device (%s) on CB", device);
                        logger.error(errorMsg, ex);
                        launchFailureCallback(failureCallback, new AgentException(errorMsg, ex));
                    }
            );
        } else {
            launchSuccessCallback(successCallback);
        }
    }

    /**
     * Check if a device is valid to use the agent
     * @param device                    Device to validate
     * @throws ConfigurationException   A configuration problem occurred
     */
    private void checkDevice(Device device) throws ConfigurationException {
        if (device == null) {
            throw new ConfigurationException("device parameter is missing.");
        }
        if (device.getDeviceEUI() == null) {
            throw new ConfigurationException("device deviceEUI is missing.");
        }
        if (device.getPort() == null) {
            throw new ConfigurationException("device port is missing.");
        }
        if (device.getEntityName() == null) {
            throw new ConfigurationException("entity name is missing");
        }
        if (device.getEntityType() == null) {
            throw new ConfigurationException("entity type is missing");
        }
    }

    /**
     * Launch a AgentSuccessCallback if not null
     * @param successCallback   AgentSuccessCallback to launch
     */
    private void launchSuccessCallback(AgentSuccessCallback successCallback) {
        if (successCallback != null) {
            successCallback.onSuccess();
        }
    }

    /**
     * Launch a AgentFailureCallback if not null
     * @param failureCallback   AgentFailureCallback to launch
     * @param exception         AgentException
     */
    private void launchFailureCallback(AgentFailureCallback failureCallback, AgentException exception) {
        if (failureCallback != null) {
            failureCallback.onFailure(exception);
        }
    }

    /**
     * Call a device's command
     * @param deviceEUI Device EUI
     * @param commandName command to launch
     * @param attribute command's attribute
     * @param callback call when command is launched
     */
    void executeCommand(String deviceEUI, String commandName, ContextAttribute attribute, BiConsumer<Boolean, Date> callback) {
        DeviceEntity device = deviceRepository.findOne(deviceEUI);
        if (device == null || converter == null) {
            if (callback != null) {
                callback.accept(false, new Date());
            }
            return;
        }

        String encodedPayload = converter.encodeDataForCommand(deviceEUI, commandName, attribute);
        if (encodedPayload != null && encodedPayload.length() > 0) {
            RegisterDeviceCommandParameter command = new RegisterDeviceCommandParameter();
            command.setData(encodedPayload);
            command.setPort(device.getPort());
            command.setConfirmed(false);
            try {
                openLpwaProvider.registerDeviceCommand(device.getDeviceEUI(), command).addCallback(
                        result -> {
                            logger.debug("Command {} sent to OpenLpwa provider for deviceEUI:{}", commandName, deviceEUI);
                            Boolean success = result.getCommandStatus() == DeviceCommand.DeviceCommandStatus.SENT;
                            if (callback != null) {
                                callback.accept(success, success ? result.getCreationTs() : new Date());
                            }
                        },
                        ex -> {
                            logger.error("Error sending command {} to OpenLpwa provider for deviceEUI:{}", commandName, deviceEUI, ex);
                            if (callback != null) {
                                callback.accept(false, new Date());
                            }
                        }
                );
            } catch (ConfigurationException e) {
                logger.error("Configuration error while executing command.", e);
                if (callback != null) {
                    callback.accept(false, new Date());
                }
            }
        } else {
            logger.warn("Payload is null or empty, command is not sent (deviceEUI:{}, commandName:{}, attribute:{}, encodedPayload:{}", deviceEUI, commandName, attribute, encodedPayload);
            if (callback != null) {
                callback.accept(false, new Date());
            }
        }
    }

    // Every 1st day of month at 1:10 AM
    @Scheduled(cron = "0 10 1 1 * ?")
    private void updateSubcriptions() {
        for (DeviceEntity device : deviceRepository.findAll()) {
            try {
                if (device.getSubscriptionId() != null) {
                    ngsiManager.updateSubscription(device.getSubscriptionId());
                }
            } catch (AgentException e) {
                logger.error("Try to unsubscribe a device with a null subscriptionId ({})", device);
            }
        }
    }

    /**
     * Manages Mqtt events
     */
    @Component
    class AgentMqttProviderCallback implements OpenLpwaMqttProviderCallback {

        private int reconnectingDelay = 5000;

        @Override
        public void connectionLost(Throwable throwable) {
            // Prevent a new connectionLost event while the reconnection
            if (!reconnecting) {
                logger.warn("Connection lost with the MQTT broker, waiting before reconnect", throwable);
                try {
                    Thread.sleep(reconnectingDelay);
                } catch (InterruptedException e) {
                }
                logger.debug("Reconnecting to the MQTT broker");
                reconnecting = true;
                start(converter,
                        () -> {
                            logger.debug("Reconnected to the MQTT broker");
                            reconnecting = false;
                        },
                        ex -> {
                            logger.error("Reconnection failed to the MQTT broker", ex);
                            reconnecting = false;
                            if (connectionLostCallback != null) {
                                connectionLostCallback.onConnectionLost();
                            }
                        }
                );
            }
        }

        @Override
        public void newMessageArrived(String deviceEUI, DeviceIncomingMessage incomingMessage) {
            DeviceEntity device = deviceRepository.findOne(deviceEUI);
            if (device == null) {
                logger.error("Device not registered, can't treat message (EUI:{})", deviceEUI);
                return;
            }

            String payload = null;
            if (incomingMessage != null && incomingMessage.getValue() != null && incomingMessage.getValue().getData() != null) {
                payload = incomingMessage.getValue().getData();
            }
            if (payload == null) {
                logger.error("Payload not found, can't treat message (EUI:{})", deviceEUI);
                return;
            }

            if (converter != null) {
                List<ContextAttribute> decodedAttributes = converter.decodeData(device.getDeviceEUI(), payload);
                try {
                    ngsiManager.updateDeviceAttributes(device, decodedAttributes);
                } catch (AgentException e) {
                    logger.error("Unable to treat incoming message (EUI:{}, message:{})", deviceEUI, incomingMessage, e);
                }
            } else {
                logger.error("Converter is not defined, message is not treated. (EUI:{}, message:{})", deviceEUI, incomingMessage);
            }
        }
    }
}
