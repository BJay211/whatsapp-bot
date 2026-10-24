import React, { useState, useEffect } from 'react';
import {
  StatusBar,
  StyleSheet,
  Text,
  View,
  ScrollView,
  TouchableOpacity,
  RefreshControl,
  ActivityIndicator,
  Platform,
  Alert,
  Toast,
} from 'react-native';
import { useQuery } from '@tanstack/react-query';
import axios from 'axios';
import { useFont } from '@expo/font';
import { useColorScheme } from 'react-native';

const BASE_URL = process.env.EXPO_PUBLIC_API_URL || 'http://10.0.2.2:3000';

export default function App() {
  const [isLoading, setIsLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [hasPaused, setHasPaused] = useState(false);
  const [themeColor] = useColorScheme();

  // Load font
  const { loadAsync } = useFont();

  useEffect(() => {
    loadAsync().then(() => {
      setIsLoading(false);
    });
  }, [loadAsync]);

  const fetchDashboardData = async () => {
    try {
      const response = await axios.get(`${BASE_URL}/api/overview`, {
        headers: { 'Authorization': `Bearer ${getToken()}` },
      });
      return response.data;
    } catch (error) {
      console.error('Failed to fetch dashboard data:', error);
      throw error;
    }
  };

  const getToken = () => {
    // In a real app, this would retrieve from secure storage
    return 'placeholder-token';
  };

  const handleRefresh = async () => {
    setRefreshing(true);
    try {
      await fetchDashboardData();
    } catch (error) {
      console.error('Refresh failed:', error);
    } finally {
      setRefreshing(false);
    }
  };

  return (
    <StatusBar style="auto" />
    <ScrollView
      contentContainerStyle={styles.scrollContainer}
      refreshControl={
        <RefreshControl
          refreshing={refreshing}
          onRefresh={handleRefresh}
          colors={[themeColor || '#3b82f6']}
        />
      }
    >
      <View style={styles.header}>
        <Text style={styles.headerTitle}>Bonjee AI</Text>
        <Text style={styles.headerSubtitle}>Your Personal AI Assistant</Text>
      </View>

      {isLoading ? (
        <ActivityIndicator size="large" color={themeColor || '#3b82f6'} />
      ) : (
        <>
          <View style={statsCardStyles(0)}>
            <Text style={statsValueStyle}>0</Text>
            <Text style={statsLabelStyle}>New Messages</Text>
          </View>
          <View style={statsCardStyles(1)}>
            <Text style={statsValueStyle}>0</Text>
            <Text style={statsLabelStyle}>Active Tasks</Text>
          </View>
          <View style={statsCardStyles(2)}>
            <Text style={statsValueStyle}>0</Text>
            <Text style={statsLabelStyle}>Upcoming Deadlines</Text>
          </View>
          <View style={statsCardStyles(3)}>
            <Text style={statsValueStyle}>0</Text>
            <Text style={statsLabelStyle}>This Week</Text>
          </View>

          <TouchableOpacity style={styles.actionButton} onPress={() => navigateTo('tasks')}>
            <Text style={styles.actionButtonText}>Tasks</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.actionButton} onPress={() => navigateTo('messages')}>
            <Text style={styles.actionButtonText}>Messages</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.actionButton} onPress={() => navigateTo('calendar')}>
            <Text style={styles.actionButtonText}>Calendar</Text>
          </TouchableOpacity>
          <TouchableOpacity style={styles.actionButton} onPress={() => navigateTo('settings')}>
            <Text style={styles.actionButtonText}>Settings</Text>
          </TouchableOpacity>

          <View style={styles.divider} />

          <View style={styles.section}>
            <Text style={styles.sectionTitle}>Recent Activity</Text>
          </View>

          <View style={styles.activityList}>
            {renderRecentActivity()}
          </View>
        </>
      )}
    </ScrollView>
  );
}

function statsCardStyles(index: number) {
  const colors = ['#3b82f6', '#10b981', '#f59e0b', '#ef4444'];
  return {
    backgroundColor: colors[index],
    padding: 20,
    borderRadius: 12,
    marginVertical: 12,
    marginHorizontal: 16,
    alignItems: 'center',
    justifyContent: 'center',
  };
}

const styles = StyleSheet.create({
  scrollContainer: {
    flexGrow: 1,
    backgroundColor: themeColor === 'dark' ? '#111827' : '#f9fafb',
    padding: 20,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: 24,
  },
  headerTitle: {
    fontSize: 28,
    fontWeight: 'bold',
    color: themeColor === 'dark' ? '#f9fafb' : '#111827',
  },
  headerSubtitle: {
    fontSize: 14,
    color: themeColor === 'dark' ? '#6b7280' : '#6b7280',
  },
  actionButton: {
    backgroundColor: themeColor === 'dark' ? '#3b82f6' : '#3b82f6',
    padding: 14,
    borderRadius: 10,
    marginVertical: 8,
    width: '100%',
    alignItems: 'center',
    marginBottom: 12,
  },
  actionButtonText: {
    color: '#fff',
    fontSize: 16,
    fontWeight: '600',
  },
  divider: {
    height: 1,
    backgroundColor: themeColor === 'dark' ? '#334155' : '#e2e8f0',
    marginVertical: 16,
  },
  section: {
    marginVertical: 12,
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: themeColor === 'dark' ? '#f9fafb' : '#111827',
    marginBottom: 8,
  },
  activityList: {
    gap: 12,
  },
  statsValueStyle: {
    fontSize: 32,
    fontWeight: 'bold',
    color: '#fff',
  },
  statsLabelStyle: {
    fontSize: 12,
    marginTop: 4,
    color: 'rgba(255, 255, 255, 0.8)',
  },
});
