import React from 'react';
import { SafeAreaView, StatusBar } from 'react-native';
import { App } from './App';

export default function Root() {
  return (
    <SafeAreaView style={{ flex: 1 }}>
      <StatusBar style="auto" />
      <App />
    </SafeAreaView>
  );
}
