package com.paf.aerovisit.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.view.RedirectView;

/** CmsWebConfig's postHandle must ignore responses without a view name (500 NullPointerException regression). No database. */
class CmsWebConfigInterceptorTest {

    // InterceptorRegistry.getInterceptors() is protected; a subclass exposes what addInterceptors registered
    private static class Registry extends InterceptorRegistry {
        List<Object> registered() { return getInterceptors(); }
    }

    @SuppressWarnings("unchecked")
    private static HandlerInterceptor interceptor() {
        // No repositories available (as in web-layer test slices): the footer falls back to empty content
        CmsWebConfig config = new CmsWebConfig(mock(ObjectProvider.class), mock(ObjectProvider.class),
                mock(ObjectProvider.class), mock(ObjectProvider.class), "uploads/exhibits");
        Registry registry = new Registry();
        config.addInterceptors(registry);
        return (HandlerInterceptor) registry.registered().get(0);
    }

    private static void postHandle(HandlerInterceptor interceptor, ModelAndView mav) throws Exception {
        interceptor.postHandle(new MockHttpServletRequest(), new MockHttpServletResponse(), new Object(), mav);
    }

    @Test
    void aModelAndViewWithoutAViewNameIsIgnored() {
        HandlerInterceptor interceptor = interceptor();
        ModelAndView redirect = new ModelAndView(new RedirectView("/admin"));
        assertThat(redirect.getViewName()).isNull();
        assertThatCode(() -> postHandle(interceptor, redirect)).doesNotThrowAnyException();
        assertThat(redirect.getModel()).isEmpty();
        assertThatCode(() -> postHandle(interceptor, new ModelAndView())).doesNotThrowAnyException();
        assertThatCode(() -> postHandle(interceptor, null)).doesNotThrowAnyException();
    }

    @Test
    void footerAndPortalViewsStillGetTheirContent() throws Exception {
        HandlerInterceptor interceptor = interceptor();
        ModelAndView register = new ModelAndView("register");
        postHandle(interceptor, register);
        assertThat(register.getModel()).containsKeys("announcements", "guidelines", "socialLinks", "contactEmail");

        ModelAndView success = new ModelAndView("success");
        postHandle(interceptor, success);
        assertThat(success.getModel()).containsKeys("socialLinks", "contactEmail").doesNotContainKey("announcements");

        ModelAndView admin = new ModelAndView("admin");
        postHandle(interceptor, admin);
        assertThat(admin.getModel()).isEmpty();
    }
}
