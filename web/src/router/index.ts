import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/Login.vue'),
      meta: { public: true },
    },
    {
      path: '/register',
      name: 'register',
      component: () => import('../views/Register.vue'),
      meta: { public: true },
    },
    {
      path: '/',
      component: () => import('../layouts/MainLayout.vue'),
      children: [
        // 首页仪表盘（B25）；对话迁至 /chat
        { path: '', name: 'home', component: () => import('../views/Home.vue') },
        { path: 'chat', name: 'agent', component: () => import('../views/Agent.vue') },
        { path: 'review', name: 'review', component: () => import('../views/Review.vue') },
        { path: 'quiz', name: 'quiz', component: () => import('../views/Quiz.vue') },
        { path: 'courses', name: 'courses', component: () => import('../views/Courses.vue') },
        { path: 'courses/:id', name: 'courseDetail', component: () => import('../views/CourseDetail.vue') },
        { path: 'questions', name: 'questions', component: () => import('../views/Questions.vue') },
        { path: 'notes', name: 'notes', component: () => import('../views/Notes.vue') },
      ],
    },
  ],
})

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (!to.meta.public && !auth.isLoggedIn) {
    return { name: 'login' }
  }
  if (to.meta.public && auth.isLoggedIn) {
    return { name: 'home' }
  }
})

export default router
