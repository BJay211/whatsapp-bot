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
  TextInput,
} from 'react-native';
import { useQuery } from '@tanstack/react-query';
import axios from 'axios';

export const TasksScreen = ({ navigation }) => {
  const [tasks, setTasks] = React.useState([]);
  const [isLoading, setIsLoading] = React.useState(true);
  const [refreshing, setRefreshing] = React.useState(false);

  const fetchTasks = async () => {
    try {
      const response = await axios.get(
        '/api/tasks',
        { headers: { Authorization: 'Bearer placeholder-token' } }
      );
      setTasks(response.data.tasks || []);
      setIsLoading(false);
    } catch (error) {
      console.error('Failed to fetch tasks:', error);
      setIsLoading(false);
      Toast.show('Failed to load tasks');
    } finally {
      setRefreshing(false);
    }
  };

  const handleRefresh = () => {
    setRefreshing(true);
    fetchTasks();
  };

  useEffect(() => {
    fetchTasks();
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
        <Text style={styles.sectionTitle}>Tasks</Text>
      </View>

      {tasks.length === 0 ? (
        <View style={styles.emptyState}>
          <Text style={styles.emptyText}>No tasks yet</Text>
          <Text style={styles.emptyHint}>Messages will create tasks automatically</Text>
        </View>
      ) : (
        <View style={styles.tasksList}>
          {tasks.map((task: any, index: number) => (
            <TouchableOpacity
              key={index}
              style={styles.taskItem}
              onPress={() => navigation.navigate('TaskDetail', { taskId: task.id })}
            >
              <View style={styles.taskContainer}>
                <View style={styles.taskPriorityBadge}>
                  {task.priority}
                </View>
                <Text style={styles.taskTitle}>
                  {task.title || 'Untitled Task'}
                </Text>
                <Text style={styles.taskDetails}>
                  {task.dueDate ? `Due: ${formatDate(task.dueDate)}` : 'No deadline'}
                </Text>
                <Text style={styles.taskStatus}>
                  {task.status || 'pending'}
                </Text>
              </View>
            </TouchableOpacity>
          ))}
        </View>
      )}
    </ScrollView>
  );
};

const formatDate = (dateString: string) => {
  const date = new Date(dateString);
  return date.toLocaleDateString(undefined, {
    weekday: 'short',
    month: 'short',
    day: 'numeric',
  });
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
  emptyHint: {
    color: '#9ca3af',
    fontSize: 14,
    marginTop: 8,
  },
  tasksList: {
    gap: 12,
  },
  taskItem: {
    backgroundColor: '#fff',
    padding: 16,
    borderRadius: 12,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.05,
    shadowRadius: 2,
    elevation: 2,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  taskContainer: {
    flex: 1,
  },
  taskPriorityBadge: {
    backgroundColor: '#e5e7eb',
    color: '#6b7280',
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 6,
    fontSize: 11,
    marginRight: 12,
    minWidth: 36,
    textAlign: 'center',
  },
  taskTitle: {
    fontSize: 15,
    fontWeight: '500',
    color: '#111827',
    marginBottom: 2,
  },
  taskDetails: {
    fontSize: 12,
    color: '#6b7280',
    marginBottom: 2,
  },
  taskStatus: {
    fontSize: 11,
    color: '#9ca3af',
    whiteSpace: 'nowrap',
  },
});