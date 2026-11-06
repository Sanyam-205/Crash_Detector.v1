# Motorcycle Crash Detection & Automatic SOS

An IoT-based motorcycle crash detection system designed to detect potential accidents and automatically alert an emergency contact with the rider's location.

Developed during **CT Buildfest 2.0**, a 30-hour coding sprint held on **October 30–31, 2026**.

## Features

- **Crash Detection:** Uses an ESP32 and MPU6050 accelerometer/gyroscope to monitor movement and detect potential crashes.
- **Automatic SOS:** Initiates an emergency alert when a crash is detected.
- **Location Sharing:** Sends the rider's location to the registered emergency contact via SMS.
- **Cancellation Mechanism:** Provides an opportunity to cancel the SOS sequence in case of a false detection.
- **Android Application:** Provides a user interface for monitoring the system and configuring the emergency contact.
- **Rechargeable Power:** Operates using an 18650 lithium-ion battery with a charging module.

## Hardware Used

- ESP32 Development Board
- MPU6050 Accelerometer and Gyroscope
- 18650 Lithium-ion Battery
- TP4056 Charging Module
- MT3608 Boost Converter

## Project Context

This project was developed as part of the IoT & Robotics track at CT Buildfest 2.0. The prototype demonstrates an approach to making motorcycle accident detection and emergency response more accessible through a compact, low-cost device.

**Note:** This is a prototype developed for demonstration and testing purposes. It has not been validated for real-world emergency use.
