import React from 'react';
import {
  View,
  Text,
  StyleSheet,
  TouchableOpacity,
  Alert,
  RefreshControl,
  ScrollView,
  ActivityIndicator,
  Platform,
  Toast,
} from 'react-native';
import { useQuery } from '@tanstack/react-query';
import axios from 'axios';

export const MessagesScreen = ({ navigation }) => {
  const [messages, setMessages] = React.useState([]);
  const [isLoading, setIsLoading] = React.useState(true);
  const [refreshing, setRefreshing] = React.useState(false);

  const fetchMessages = async () => {
    try {
      const response = await axios.get(
        '/api/messages',
        { headers: { Authorization: 'Bearer placeholder-token' } }
      );
      setMessages(response.data.messages || []);
      setIsLoading(false);
    } catch (error) {
      console.error('Failed to fetch messages:', error);
      setIsLoading(false);
      Toast.show('Failed to load messages');
    } finally {
      setRefreshing(false);
    }
  };

  const handleRefresh = () => {
    setRefreshing(true);
    fetchMessages();
  };

  useEffect(() => {
    fetchMessages();
  }, []);

  if (isLoading) {
    return (
      <ActivityIndicator size="large" color="blue" />
    );
  }

  return (
    <ScrollView
      contentContainerStyle={styles.container}
      refreshControl={
        <RefreshControl
          refreshing={refreshing}
          onRefresh={handleRefresh}
          colors={['#3b82f6']}
        />
      }
    >
      <View style={styles.section}>
        <Text style={styles.sectionTitle}>Messages</Text>
      </View>

      {messages.length === 0 ? (
        <View style={styles.emptyState}>
          <Text style={styles.emptyText}>No messages yet</Text>
        </View>
      ) : (
        <View style={styles.messagesList}>
          {messages.map((msg: any, index: number) => (
            <TouchableOpacity
              key={index}
              style={styles.messageItem}
              onPress={() => navigation.navigate('MessageDetail', { messageId: msg.id })}
            >
              <View style={styles.messageContainer}>
                <Text style={styles.messageSender}>{msg.senderName || 'Unknown'}</Text>
                <Text style={styles.messagePreview}>
                  {msg.content?.substring(0, 100) || 'No content'}
                </Text>
                <Text style={styles.messageTime}>
                  {new Date(msg.timestamp).toLocaleTimeString()}
                </Text>
              </View>
            </TouchableOpacity>
          ))}
        </View>
      )}
    </ScrollView>
  );
};

const styles = StyleSheet.create({
  container: {
    flexGrow: 1,
    backgroundColor: '#f9fafb',
    padding: 20,
  },
  section: {
    marginBottom: 20,
    paddingBottom: 12,
    borderBottom: '1px solid #e2e8f0',
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: '600',
    color: '#111827',
    marginBottom: 8,
  },
  emptyState: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 40,
  },
  emptyText: {
    color: '#6b7280',
    fontSize: 16,
  },
  messagesList: {
    gap: 12,
  },
  messageItem: {
    backgroundColor: '#fff',
    padding: 16,
    borderRadius: 12,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.05,
    shadowRadius: 2,
    elevation: 2,
  },
  messageContainer: {
    flexDirection: 'row',
    alignItems: 'flex-start',
  },
  messageSender: {
    fontWeight: '600',
    fontSize: 14,
    color: '#111827',
    marginBottom: 4,
  },
  messagePreview: {
    fontSize: 13,
    color: '#475569',
    marginBottom: 4,
    lineHeight: 18,
  },
  messageTime: {
    fontSize: 11,
    color: '#9ca3af',
    marginTop: 2,
  },
});