import React from 'react';
import { NavigationContainer, NavigationContainerRef } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { NavigationContainerProps } from '@react-navigation/native';
import { MessagesScreen } from './components/MessagesScreen';
import { TasksScreen } from './components/TasksScreen';
import { CalendarScreen } from './components/CalendarScreen';
import { SettingsScreen } from './components/SettingsScreen';
import { BriefingScreen } from './components/BriefingScreen';

export type RootStackParamList = {
  Messages: undefined;
  Tasks: undefined;
  Calendar: undefined;
  Settings: undefined;
  Briefing: undefined;
};

const Stack = createNativeStackNavigator<RootStackParamList>();

export const NavigationRef = createRef<NavigationContainerRef<RootStackParamList>>();

export const RootNavigator = () => {
  return (
    <NavigationContainer
      ref={NavigationRef}
      linking={{ prefixes: ['bonjee://'] }}
    >
      <Stack.Navigator
        screenOptions={{
          headerShown: false,
          animation: 'fade',
        }}
      >
        <Stack.Screen name="Messages" component={MessagesScreen} />
        <Stack.Screen name="Tasks" component={TasksScreen} />
        <Stack.Screen name="Calendar" component={CalendarScreen} />
        <Stack.Screen name="Settings" component={SettingsScreen} />
        <Stack.Screen name="Briefing" component={BriefingScreen} />
      </Stack.Navigator>
    </NavigationContainer>
  );
};