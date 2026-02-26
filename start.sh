#!/bin/bash

echo "Compiling Java..."
./gradlew compileJava

echo "Running the App..."
./gradlew run
