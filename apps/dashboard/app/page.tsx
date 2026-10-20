import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { AlertCircle, Clock, CheckCircle, MessageSquare, Bell } from "lucide-react"

export default function Home() {
  return (
    <div className="min-h-screen bg-background">
      <header className="border-b">
        <div className="container mx-auto px-4 py-4">
          <h1 className="text-2xl font-bold">Bonjee Bot</h1>
          <p className="text-muted-foreground">Your personal briefing assistant</p>
        </div>
      </header>
      
      <main className="container mx-auto px-4 py-6">
        <div className="mb-6">
          <Card className="border-primary/50">
            <CardHeader>
              <CardTitle className="flex items-center gap-2">
                <AlertCircle className="h-5 w-5 text-primary" />
                Latest Brief
              </CardTitle>
              <CardDescription>No summaries yet. Send some messages or notifications to trigger your first brief.</CardDescription>
            </CardHeader>
            <CardContent>
              <p className="text-muted-foreground">Waiting for activity...</p>
            </CardContent>
          </Card>
        </div>

        <div className="grid gap-4 md:grid-cols-2 lg:grid-cols-4 mb-6">
          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Urgent</CardTitle>
              <AlertCircle className="h-4 w-4 text-destructive" />
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold">0</div>
              <p className="text-xs text-muted-foreground">Requires immediate attention</p>
            </CardContent>
          </Card>
          
          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Deadlines</CardTitle>
              <Clock className="h-4 w-4 text-orange-500" />
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold">0</div>
              <p className="text-xs text-muted-foreground">Upcoming deadlines</p>
            </CardContent>
          </Card>
          
          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Action Items</CardTitle>
              <CheckCircle className="h-4 w-4 text-blue-500" />
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold">0</div>
              <p className="text-xs text-muted-foreground">To do</p>
            </CardContent>
          </Card>
          
          <Card>
            <CardHeader className="flex flex-row items-center justify-between space-y-0 pb-2">
              <CardTitle className="text-sm font-medium">Messages</CardTitle>
              <MessageSquare className="h-4 w-4 text-green-500" />
            </CardHeader>
            <CardContent>
              <div className="text-2xl font-bold">0</div>
              <p className="text-xs text-muted-foreground">New since last summary</p>
            </CardContent>
          </Card>
        </div>

        <div className="grid gap-4 md:grid-cols-2">
          <Card>
            <CardHeader>
              <CardTitle>Quick Links</CardTitle>
              <CardDescription>Navigate your dashboard</CardDescription>
            </CardHeader>
            <CardContent className="space-y-2">
              <a href="/timeline" className="block p-2 hover:bg-accent rounded-md">Timeline</a>
              <a href="/deadlines" className="block p-2 hover:bg-accent rounded-md">Deadlines</a>
              <a href="/actions" className="block p-2 hover:bg-accent rounded-md">Action Items</a>
              <a href="/settings" className="block p-2 hover:bg-accent rounded-md">Settings</a>
            </CardContent>
          </Card>
          
          <Card>
            <CardHeader>
              <CardTitle>System Status</CardTitle>
              <CardDescription>Check your connections</CardDescription>
            </CardHeader>
            <CardContent className="space-y-2">
              <div className="flex items-center justify-between">
                <span className="text-sm">n8n</span>
                <span className="text-xs text-muted-foreground">Configure in env</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-sm">Evolution API</span>
                <span className="text-xs text-muted-foreground">Configure</span>
              </div>
              <div className="flex items-center justify-between">
                <span className="text-sm">Android Notifier</span>
                <span className="text-xs text-muted-foreground">Sideload APK</span>
              </div>
            </CardContent>
          </Card>
        </div>
      </main>
    </div>
  )
}
