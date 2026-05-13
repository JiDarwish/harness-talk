package com.example.bookshelf.archunit;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class BookshelfArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.example.bookshelf");
    }

    @Test
    void repositories_must_be_in_persistence_package() {
        classes().that().areAssignableTo(JpaRepository.class)
                .should().resideInAPackage("..persistence..")
                .because("""
                        CONVENTION VIOLATION: Repository interfaces must be in the 'persistence' package.
                        WHY: The team uses 'persistence' (not 'repository') as the package name for all \
                        Spring Data interfaces. This is enforced for consistency across the codebase.
                        HOW TO FIX: Move the repository interface to com.example.bookshelf.persistence/.""")
                .check(classes);
    }

    @Test
    void services_must_be_in_service_package() {
        classes().that().areAnnotatedWith(Service.class)
                .should().resideInAPackage("..service..")
                .because("""
                        CONVENTION VIOLATION: @Service classes must be in the 'service' package.
                        WHY: Business logic is organized in com.example.bookshelf.service/. Placing \
                        services elsewhere breaks the layered architecture.
                        HOW TO FIX: Move the @Service class to com.example.bookshelf.service/.""")
                .check(classes);
    }

    @Test
    void controllers_must_be_in_api_package() {
        classes().that().areAnnotatedWith(RestController.class)
                .should().resideInAPackage("..api..")
                .because("""
                        CONVENTION VIOLATION: @RestController classes must be in the 'api' package.
                        WHY: REST endpoints are organized in com.example.bookshelf.api/. This keeps \
                        the web layer separate from business logic.
                        HOW TO FIX: Move the @RestController class to com.example.bookshelf.api/.""")
                .check(classes);
    }

    @Test
    void service_should_not_depend_on_api() {
        noClasses().that().resideInAPackage("..service..")
                .should().dependOnClassesThat().resideInAPackage("..api..")
                .because("""
                        DEPENDENCY VIOLATION: Service layer must not depend on the API layer.
                        WHY: The service layer contains business logic that should be independent of \
                        the web transport. Depending on controllers or request/response objects couples \
                        the layers incorrectly.
                        HOW TO FIX: Move any shared types to the 'domain' package. The service should \
                        only depend on 'domain' and 'persistence' packages.""")
                .check(classes);
    }

    @Test
    void persistence_should_not_depend_on_service() {
        noClasses().that().resideInAPackage("..persistence..")
                .should().dependOnClassesThat().resideInAPackage("..service..")
                .because("""
                        DEPENDENCY VIOLATION: Persistence layer must not depend on the service layer.
                        WHY: Repositories should only depend on domain entities. Depending on service \
                        classes creates a circular dependency between layers.
                        HOW TO FIX: If the repository needs a type from the service layer, that type \
                        likely belongs in 'domain' instead. Move it there.""")
                .check(classes);
    }

    @Test
    void service_methods_must_return_result() {
        methods().that().areDeclaredInClassesThat().resideInAPackage("..service..")
                .and().areDeclaredInClassesThat().areAnnotatedWith(Service.class)
                .and().arePublic()
                .should(returnResultType())
                .because("""
                        CONVENTION VIOLATION: Public service methods must return Result<T, BookshelfError>.
                        WHY: The team uses a Result sealed type instead of throwing exceptions. This \
                        makes error handling explicit and forces callers to handle both success and \
                        failure cases via pattern matching.
                        HOW TO FIX: Change the method return type to Result<T, BookshelfError>. \
                        Wrap successful returns in Result.success(value) and error cases in \
                        Result.failure(new BookshelfError.XxxError(...)). Do not throw exceptions.""")
                .check(classes);
    }

    private static ArchCondition<JavaMethod> returnResultType() {
        return new ArchCondition<>("return Result<?, ?>") {
            @Override
            public void check(JavaMethod method, ConditionEvents events) {
                String returnType = method.getRawReturnType().getName();
                if (!returnType.equals("com.example.bookshelf.domain.Result")) {
                    String message = String.format(
                            "Method %s returns %s instead of Result<T, BookshelfError>",
                            method.getFullName(), method.getRawReturnType().getSimpleName());
                    events.add(SimpleConditionEvent.violated(method, message));
                }
            }
        };
    }
}
